package io.euhedral_execution.core.control_plane;

import static io.euhedral_execution.core.utils.MathFunctions.unsignedMultiplyHigh;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.internal.Constants;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.data_structures.queues.PlainQueue;
import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SocketSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public class ControlPlaneShard {

    protected static final VarHandle HANDLE = MethodHandles.arrayElementVarHandle(
            LatticeEdge[].class);

    public static ControlPlaneShard createBaseShard(@NonNull CloneableObject cloneableObject) {
        Objects.requireNonNull(cloneableObject);
        return new ControlPlaneShard(-1, ControlPlaneShard.class.getSimpleName(), cloneableObject,
                Duration.ZERO);
    }

    public static ControlPlaneShard createBaseShard(String name,
            @NonNull CloneableObject cloneableObject) {
        Objects.requireNonNull(cloneableObject);
        return new ControlPlaneShard(-1, name, cloneableObject, Duration.ZERO);
    }

    protected final Logger logger;
    protected final Duration shutdownTimeout;

    @Getter
    protected final int socket;
    @Getter
    protected final String shardName;

    protected final AtomicLong currentVersion = new AtomicLong(Integer.MIN_VALUE);

    protected final AtomicBoolean primed = new AtomicBoolean(false);
    protected final AtomicBoolean started = new AtomicBoolean(false);
    protected final AtomicBoolean rebalancing = new AtomicBoolean(false);
    protected final AtomicInteger coresToDrain = new AtomicInteger(0);

    protected final AtomicReference<LatticeVertex> coreDistributor = new AtomicReference<>();

    protected final CloneableObject cloneableObject;
    protected final AtomicReference<int[]> activeCoreIds = new AtomicReference<>(new int[0]);
    protected final AtomicReference<CloneableObject[]> clones = new AtomicReference<>(
            new CloneableObject[0]);

    protected final LatticeEdge[] coreHandles = new LatticeEdge[SystemInfo.getMaxCoreId() + 1];
    protected volatile ExecutorService shardExecutor;

    protected ControlPlaneShard(int socket, String shardName,
            CloneableObject obj, Duration shutdownTimeout) {
        this.logger = LoggerFactory.getLogger(Constants.getLoggerName(shardName));
        this.shutdownTimeout = shutdownTimeout;
        this.socket = socket;
        this.shardName = shardName;
        this.cloneableObject = obj;
    }

    public int getActiveCores() {
        return this.activeCoreIds.getAcquire().length;
    }

    public void start(SocketSnapshot snapshot, EffectiveSocketTopology topology,
            LatticeEdge upstream) {
        if (!this.started.compareAndSet(false, true)) {
            return;
        }
        this.logger.info("Starting.");
        this.shardExecutor = Executors.newFixedThreadPool(topology.effectiveCores().length(),
                r -> new Thread(r, this.shardName + "-ExecutorService"));

        SocketInfo info = SystemInfo.getSocketInfo(snapshot.socketId());
        long sizeL3 = SystemInfo.socketL3Cache(snapshot.socketId());
        int cores = info.getCoreSet().cardinality();
        long capacity = (long) (sizeL3 * 0.7);
        capacity /= QueueUtils.REFERENCE_SIZE;

        long chunkSize = capacity == 0 ? 0 : QueueUtils.roundChunkSize(capacity);

        String partChunk = NumberFormat.getNumberInstance().format(chunkSize / Math.max(cores, 1));
        String strCap = NumberFormat.getNumberInstance().format(cores * chunkSize);
        logger.debug("L3 Cache: Partitions: {} PartitionChunkSize: {} Capacity: {}", cores,
                partChunk, strCap);

        LatticeVertex coreDistributor = new LatticeVertex(this.shardName + "-CoreDistributor",
                SystemInfo.getMaxCoreId() + 1, this::route, (int) capacity,
                RoutingPolicy.SOCKET_LOCAL);
        this.coreDistributor.set(coreDistributor);
        coreDistributor.addUpstream(upstream);
        update(snapshot, topology);
    }

    /// Routes work based on their policy level or uses default global routing.
    protected int route(AbstractFrame frame, int mapSize) {
        RoutingPolicy policy = frame.getRoutingPolicy();
        CpuInfo location = frame.getOrigin();
        if (policy.level > RoutingPolicy.SOCKET_LOCAL.level && location != null) {
            int core = location.core();
            LatticeEdge handle = (LatticeEdge) HANDLE.getOpaque(this.coreHandles, core);

            if (handle != null) {
                return core;
            }
        }

        // Default routing
        long rotated = Long.rotateLeft(frame.getRoutingHash(), 31);
        return (int) unsignedMultiplyHigh(rotated, mapSize);
    }

    /// Hands out the core-specific hardware utilization reports or initiates a rebalance on
    /// topology change.
    public void update(SocketSnapshot snapshot, EffectiveSocketTopology topology) {
        if (!this.started.get()) {
            this.logger.error("Cannot update if not started.");
            return;
        }
        if (this.rebalancing.get()) {
            this.logger.warn("Cannot update while rebalancing. CoresToDrain: {}",
                    this.coresToDrain.get());
            return;
        }

        int nextVersion = topology.version();
        if (!this.primed.getOpaque()) {
            this.logger.info("Priming clones for socket topology V{}", nextVersion);
            handleTopologyChange(snapshot, topology);
        } else if (this.currentVersion.getAcquire() != nextVersion) {
            this.logger.warn(
                    "Detected change in topology. Initiating rebalance for socket topology V{}",
                    nextVersion);
            handleTopologyChange(snapshot, topology);
        } else {
            int[] active = this.activeCoreIds.getOpaque();
            CloneableObject[] clones = this.clones.getOpaque();
            for (int coreId : active) {
                updateClone(clones[coreId], snapshot.coreSnapshots()[coreId]);
            }
        }
    }

    /// Spawns new clones if a cores become available. Shuts down clones if the cores are removed.
    /// Updates existing clones with their new utilization reports.
    protected void handleTopologyChange(SocketSnapshot snapshot, EffectiveSocketTopology topology) {
        if (!this.rebalancing.compareAndSet(false, true)) {
            return;
        }
        this.currentVersion.setRelease(topology.version());

        BitSet newCores = topology.effectiveCores();
        remapIngestController(newCores);

        CloneableObject[] oldClones = this.clones.getPlain();
        createNextClones(snapshot, topology);

        if (!this.primed.getOpaque()) {
            for (var clone : this.clones.getPlain()) {
                if (clone != null) {
                    clone.setDrainMode(false);
                }
            }
            this.coreDistributor.get().setDrain(false);
            this.primed.set(true);
            this.rebalancing.set(false);
            logger.info("Priming Complete");
        } else {
            drainAndPruneClones(oldClones, snapshot);
        }
    }

    protected void remapIngestController(BitSet newCores) {
        this.logger.trace("Remapping ingest controller.");
        LatticeVertex distributor = this.coreDistributor.get();
        distributor.setDrain(true);
        for (int i = newCores.nextSetBit(0); i >= 0; i = newCores.nextSetBit(i + 1)) {
            LatticeEdge handle = this.coreHandles[i];
            if (handle == null) {
                handle = new LatticeEdge(distributor.getDrainFlag());
                this.coreHandles[i] = handle;
                HANDLE.setRelease(this.coreHandles, i, handle);
            }
        }
        distributor.setDownstreamMapping(newCores, this.coreHandles);
    }

    protected void createNextClones(SocketSnapshot snapshot,
            EffectiveSocketTopology topology) {
        this.logger.info("Creating new clones");
        CloneableObject[] clones = this.clones.getOpaque();
        BitSet newCores = topology.effectiveCores();

        int idx = 0;
        int[] nextCores = new int[newCores.cardinality()];
        CloneableObject[] nextClones = new CloneableObject[topology.effectiveCores().length()];
        for (int i = newCores.nextSetBit(0); i >= 0; i = newCores.nextSetBit(i + 1)) {
            if (i >= clones.length || clones[i] == null) {
                nextClones[i] = spawnClone(i, snapshot.coreSnapshots()[i], nextClones);
            } else {
                nextClones[i] = clones[i];
                final int id = i;
                CompletableFuture.runAsync(
                        () -> updateClone(clones[id], snapshot.coreSnapshots()[id]),
                        this.shardExecutor);
            }
            nextCores[idx++] = i;
        }
        this.clones.setRelease(nextClones);
        this.activeCoreIds.setRelease(nextCores);
    }

    protected void updateClone(CloneableObject clone, CoreSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        if (!clone.isStarted()) {
            this.logger.trace("Starting clone on core {}", snapshot.coreId());
            clone.start();
        }
        clone.update(snapshot);
    }

    /// Creates a clone on the core, links it to the core distributor, starts it, and updates it.
    protected CloneableObject spawnClone(int coreId, CoreSnapshot snapshot,
            CloneableObject[] nextClones) {
        this.logger.trace("Spawning clone on core {}", snapshot.coreId());
        CloneConfig config = new CloneConfig(this.shardName, coreId, snapshot.effectiveCpus());

        CloneableObject clone = this.cloneableObject.clone(config);
        nextClones[coreId] = clone;

        clone.input(this.coreHandles[coreId]);

        clone.setDrainMode(this.rebalancing.get());
        clone.start();
        clone.update(snapshot);
        return clone;
    }

    /// Drains all active clones and removes clones that are not in the next active set.
    protected void drainAndPruneClones(CloneableObject[] oldClones, SocketSnapshot snapshot) {
        this.logger.info("Draining and pruning clones.");

        CloneableObject[] currClones = this.clones.getOpaque();

        Set<Integer> deadClones = new HashSet<>();
        PlainQueue<CloneableObject> clones = new PlainQueue<>(currClones.length);
        for (int core = 0; core < oldClones.length; core++) {
            CloneableObject clone = oldClones[core];
            if(clone == null) {
                continue;
            }

            this.coresToDrain.incrementAndGet();
            clone.setDrainMode(true);
            clones.offer(clone);
            if (core >= currClones.length || currClones[core] == null) {
                this.logger.info("Removing clone on core {}", core);
                this.coreHandles[core] = null;
                deadClones.add(core);
            }
        }

        clones.drain(clone -> CompletableFuture.runAsync(() -> {
            int core = clone.getCore();

            Thread.currentThread().setName(this.shardName + "-" + core);
            final long deadline =
                    System.nanoTime() + this.shutdownTimeout.toNanos();

            SpinWait.awaitWhile(() -> !clone.isDrained() && System.nanoTime() < deadline);

            if(deadClones.contains(core) || System.nanoTime() >= deadline) {
                closeClone(clone);
                if (!deadClones.contains(core)) {
                    this.logger.info("Restarting clone on core {}", core);
                    spawnClone(core, snapshot.coreSnapshots()[core], currClones);
                }
            }

            int remaining = this.coresToDrain.decrementAndGet();
            clone.setDrainMode(false);
            if (remaining == 0) {
                this.logger.info("Drain complete.");
            }
            tryRestartIngest();
        }, this.shardExecutor));
    }

    protected final void closeClone(CloneableObject clone) {
        try {
            clone.close();
        } catch (Exception e) {
            this.logger.error("Failed to shut down clone on core {}", clone.getCore());
        } finally {
            clone.dumpLocks();
        }
    }

    /// Restarts ingest if all cores are drained.
    protected void tryRestartIngest() {
        if (this.coresToDrain.get() == 0) {
            this.logger.info("Restarting ingest.");
            this.rebalancing.set(false);
        }
    }

    /// Shuts down all cores under the shard's control.
    public void shutDownShard(AtomicInteger shutDownCounter) {
        if (!this.started.compareAndSet(true, false)) {
            return;
        }

        this.logger.info("Shutting down...");

        int[] active = this.activeCoreIds.getAcquire();
        CloneableObject[] clones = this.clones.getAcquire();

        AtomicInteger drainCounter = new AtomicInteger(active.length);
        for (int i = 0; i < clones.length; i++) {
            if (clones[i] == null) {
                continue;
            }

            CloneableObject clone = clones[i];
            clones[i] = null;

            shutdownCore(i, clone, drainCounter, shutDownCounter);
        }
        this.activeCoreIds.setRelease(new int[0]);
        this.clones.setRelease(new CloneableObject[0]);
        Arrays.fill(this.coreHandles, null);
        this.shardExecutor.shutdown();
        this.shardExecutor = null;
        this.primed.set(false);
        this.logger.info("Shutdown complete.");
    }

    /// Attempts to gracefully shut down a core. Forcefully shuts them down if they time out.
    private void shutdownCore(int coreId, CloneableObject oldClone,
            AtomicInteger drainSignal, AtomicInteger shutDownCounter) {
        this.logger.trace("Shutting down clone on core {}", coreId);
        oldClone.setDrainMode(true);

        CompletableFuture.runAsync(() -> {
            Thread.currentThread().setName(this.shardName + "-" + coreId);
            long deadline = System.nanoTime() + this.shutdownTimeout.toNanos();
            try {
                SpinWait.awaitWhile(() -> !oldClone.isDrained() && System.nanoTime() < deadline);
                if(!oldClone.isDrained() && System.nanoTime() >= deadline) {
                    this.logger.error("Clone on core {} timed out. Forcing shutdown.", coreId);
                    oldClone.close();
                }
            } catch (Exception e) {
                this.logger.error("Shutdown cleanup failed for Core {}", coreId, e);
            } finally {
                try {
                    oldClone.dumpLocks();
                    oldClone.close();
                } catch (Exception e) {
                    this.logger.error("CRITICAL: Worker on core {} failed to close.", coreId, e);
                } finally {
                    drainSignal.decrementAndGet();
                    if (drainSignal.get() == 0) {
                        shutDownCounter.decrementAndGet();
                    }
                }
            }
        }, this.shardExecutor);
    }

    long resetForNextTrial(long deadlineNanos) {
        if (!this.started.getAcquire()) {
            return 0;
        }
        while (this.rebalancing.getAcquire() && System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }
        if (this.rebalancing.getAcquire()) {
            throw new IllegalStateException(
                    "Timed out waiting for shard rebalance before trial reset: " + this.shardName);
        }

        LatticeVertex distributor = this.coreDistributor.getAcquire();
        if (distributor == null) {
            return 0;
        }

        distributor.setDrain(true);
        CloneableObject[] activeClones = this.clones.getAcquire();
        for (CloneableObject clone : activeClones) {
            if (clone != null) {
                clone.setDrainMode(true);
            }
        }

        long cleared = distributor.clearCachedFrames();
        try {
            for (CloneableObject clone : activeClones) {
                if (clone != null) {
                    cleared += clone.resetForNextTrial(deadlineNanos);
                }
            }
            return cleared;
        } finally {
            for (CloneableObject clone : activeClones) {
                if (clone != null) {
                    clone.setDrainMode(false);
                }
            }
            distributor.setDrain(false);
        }
    }

    public boolean isStarted() {
        if(!this.started.getAcquire()) {
            return false;
        }
        CloneableObject[] clones = this.clones.getAcquire();
        for(var clone : clones) {
            if(clone != null && !clone.ready()) {
                return false;
            }
        }
        return true;
    }

    /// Whether the shard is rebalancing. Rebalancing shards do not accept incoming work.
    public boolean isRebalancing() {
        return this.rebalancing.get();
    }

    /// Whether all queues are empty and all in-progress work is completed for all CPUs managed by
    /// this ControlPlaneLattice.
    public boolean isDrained() {
        if (!this.started.get()) {
            return true;
        }
        if (this.rebalancing.get()) {
            return false;
        }

        LatticeVertex coreDistributor = this.coreDistributor.get();
        if (coreDistributor != null && !coreDistributor.isDrained()) {
            return false;
        }

        boolean drained = true;
        CloneableObject[] clones = this.clones.getAcquire();
        for (int i = 0; i < clones.length && drained; i++) {
            CloneableObject clone = clones[i];
            if (clone != null) {
                drained &= clone.isDrained();
            }
        }
        return drained;
    }

    /// Creates a shallow copy of the shard.
    public ControlPlaneShard clone(int socketId, String rootName, Duration shutdownTimeout) {
        return new ControlPlaneShard(socketId, rootName + "-" + this.shardName + "-" + socketId,
                this.cloneableObject, shutdownTimeout);
    }

    /// Forcefully shuts down all cores.
    public void close() {
        this.started.set(false);
        this.logger.info("Closing.");
        this.coreDistributor.getAndUpdate(distributor -> {
            if (distributor != null) {
                try {
                    distributor.close();
                } catch (Exception e) {
                    this.logger.error("CRITICAL: Failed to close the ingest controller.", e);
                }
            }
            return null;
        });

        CloneableObject[] clones = this.clones.getAcquire();
        for (int i = 0; i < clones.length; i++) {
            CloneableObject clone = clones[i];
            if (clone != null) {
                try {
                    clone.close();
                } catch (Exception e) {
                    this.logger.error("Failed to close clone.", e);
                }
                clone.dumpLocks();
                clones[i] = null;
            }
        }

        if (this.shardExecutor != null) {
            this.shardExecutor.shutdownNow();
        }
        this.logger.info("Closed.");
    }
}
