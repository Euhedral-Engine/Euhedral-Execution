package euhedral.io.control_plane;

import static euhedral.io.utils.MathFunctions.unsignedMultiplyHigh;

import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.hardware_utils.common.SystemUtilization.SocketSnapshot;
import euhedral.io.config.CloneConfig;
import euhedral.io.flow_control.LatticeEdge;
import euhedral.io.flow_control.LatticeVertex;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.CloneableObject;
import java.time.Duration;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import org.jctools.queues.SpscArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControlPlaneShard implements AutoCloseable {

    protected final Logger logger;

    @Getter
    protected final int shardId;
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
    protected final AtomicReference<LatticeEdge[]> coreHandles = new AtomicReference<>(
            new LatticeEdge[0]);
    protected final AtomicReference<int[]> reverseMapping = new AtomicReference<>(new int[0]);
    protected volatile ExecutorService shardExecutor;

    public ControlPlaneShard(int shardId, String shardName,
            CloneableObject obj) {
        this.logger = LoggerFactory.getLogger(shardName);
        this.shardId = shardId;
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
                (r) -> new Thread(r, this.shardName + "+ExecutorService"));
        LatticeVertex coreDistributor = new LatticeVertex(this.shardName + "-CoreDistributor",
                topology.effectiveCores().length(), this::route, false);
        this.coreDistributor.set(coreDistributor);
        coreDistributor.addUpstream(upstream);
        update(snapshot, topology);
    }

    /// Routes work based on their policy level or uses default global routing.
    protected int route(AbstractFrame frame, int mapSize) {
        RoutingPolicy policy = frame.getRoutingPolicy();
        if (policy != null && policy.level > RoutingPolicy.SOCKET_LOCAL.level) {
            int[] reverseMapping = this.reverseMapping.getOpaque();
            CpuInfo location = frame.getOrigin();
            int node = location != null ? location.core() : -1;

            if (node >= 0 && node < reverseMapping.length) {
                node = reverseMapping[node];
                if(node < mapSize) {
                    return node;
                }
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
            this.logger.info("Initializing clones for socket topology V{}", nextVersion);
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

        // Cut ingest
        LatticeVertex distributor = this.coreDistributor.get();
        distributor.setDrain(true);

        BitSet newCores = topology.effectiveCores();
        int[] nextCores = new int[newCores.cardinality()];

        LatticeEdge[] currHandles = this.coreHandles.getAcquire();
        LatticeEdge[] nextHandles = new LatticeEdge[topology.effectiveCores().length()];
        CloneableObject[] clones = this.clones.getOpaque();
        CloneableObject[] nextClones = new CloneableObject[topology.effectiveCores().length()];

        // Build reverse mapping for fast core-local routing
        int idx = 0;
        int[] reverseMapping = new int[SystemInfo.getMaxCoreId() + 1];
        for (int i = newCores.nextSetBit(0); i >= 0; i = newCores.nextSetBit(i + 1)) {
            nextHandles[i] = i >= clones.length ? null : currHandles[i];
            nextHandles[i] =
                    nextHandles[i] == null ? new LatticeEdge(distributor.getDrainFlag())
                            : nextHandles[i];
            reverseMapping[i] = idx++;
        }
        this.coreHandles.setRelease(nextHandles);

        distributor.setDownstreamMapping(newCores, nextHandles);
        this.reverseMapping.set(reverseMapping);

        idx = 0;
        // Create new clones
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

        if(!this.primed.getOpaque()) {
            this.clones.setRelease(nextClones);
            this.activeCoreIds.setRelease(nextCores);
            this.coreDistributor.get().setDownstreamMapping(newCores, nextHandles);
            for (var clone : nextClones) {
                if (clone != null) {
                    clone.setDrainMode(false);
                }
            }
            this.coreDistributor.get().setDrain(false);
            this.primed.set(true);
            this.rebalancing.set(false);
        } else {
            drainAndPruneClones(newCores, snapshot, nextClones);
            this.clones.setRelease(nextClones);
            this.activeCoreIds.setRelease(nextCores);
        }
    }

    private void updateClone(CloneableObject clone, CoreSnapshot snapshot) {
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
    private CloneableObject spawnClone(int coreId, CoreSnapshot snapshot,
            CloneableObject[] nextClones) {
        CloneConfig config = new CloneConfig(this.shardName, coreId, snapshot.quotaCpus(),
                snapshot.effectiveCpus());

        CloneableObject clone = this.cloneableObject.clone(config);
        nextClones[coreId] = clone;

        clone.input(this.coreHandles.getPlain()[coreId]);

        clone.setDrainMode(this.rebalancing.get());
        this.logger.trace("Starting clone on core {}", snapshot.coreId());
        clone.start();
        clone.update(snapshot);
        return clone;
    }

    /// Drains all active clones and removes clones that are not in the next active set.
    private void drainAndPruneClones(BitSet active, SocketSnapshot snapshot,
            CloneableObject[] nextClones) {
        this.logger.info("Draining and pruning clones.");

        CloneableObject[] currClones = this.clones.getOpaque();
        Set<Integer> deadClones = new HashSet<>();
        SpscArrayQueue<CloneableObject> clones = new SpscArrayQueue<>(
                currClones.length);
        for (int i = 0; i < currClones.length; i++) {
            CloneableObject clone = currClones[i];
            if (clone != null) {
                if (!active.get(i)) {
                    currClones[i] = null;
                    deadClones.add(i);
                }
                clone.setDrainMode(true);
                this.coresToDrain.incrementAndGet();
                clones.relaxedOffer(clone);
            }
        }

        if (this.coresToDrain.get() == 0) {
            tryRestartIngest(active);
        }

        clones.drain(clone -> CompletableFuture.runAsync(() -> {
            Thread.currentThread().setName(this.shardName + "-" + clone.getCore());
            final long deadline =
                    System.nanoTime() + Duration.ofSeconds(1).toNanos();

            while (!clone.isDrained()) {
                LockSupport.parkNanos(5_000);
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (System.nanoTime() >= deadline) {
                this.logger.error(
                        "Failed to drain clone on core {}. Forcing shutdown and restarting if core is assigned.",
                        clone.getCore());
                try {
                    clone.close();
                } catch (Exception e) {
                    this.logger.error("Forced shutdown failed for core {}", clone.getCore(), e);
                } finally {
                    clone.dumpLocks();
                }
                if (!deadClones.contains(clone.getCore())) {
                    int core = clone.getCore();
                    this.logger.info("Restarting clone on core {}", core);
                    spawnClone(core, snapshot.coreSnapshots()[core], nextClones);
                }
            } else if (deadClones.contains(clone.getCore())) {
                int core = clone.getCore();
                this.logger.info("Gracefully shutting down clone on core {}", core);
                try {
                    clone.close();
                } catch (Exception e) {
                    this.logger.error("Failed to shut down clone on core {}", core);
                } finally {
                    clone.dumpLocks();
                }
            }
            int remaining = this.coresToDrain.decrementAndGet();
            clone.setDrainMode(false);
            if (remaining == 0) {
                this.logger.info("Drain complete.");
            }
            tryRestartIngest(active);
        }, this.shardExecutor));
    }

    /// Restarts ingest if all cores are drained.
    protected void tryRestartIngest(BitSet active) {
        if (this.coresToDrain.get() == 0) {
            this.logger.info("Restarting ingest.");
            long deadline =
                    System.nanoTime() + Duration.ofMillis(500).toNanos();
            while (!this.coreDistributor.get()
                    .setDownstreamMapping(active, this.coreHandles.getPlain())) {
                LockSupport.parkNanos(5_000);
                if (System.nanoTime() >= deadline) {
                    this.logger.error("Failed to restart ingest. Closing.");
                    try {
                        close();
                    } catch (Exception e) {
                        this.logger.error("CRITICAL. Failed to close.", e);
                    }
                    this.rebalancing.set(false);
                    return;
                }
            }
            this.rebalancing.set(false);
        }
    }

    /// Shuts down all cores under the shard's control.
    public void shutDownShard(AtomicInteger shutDownCounter) {
        if (!this.started.compareAndSet(true, false)) {
            return;
        }

        this.logger.info("Shutting down.");

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
        this.coreHandles.setRelease(new LatticeEdge[0]);
        this.shardExecutor.shutdown();
        this.shardExecutor = null;
        this.primed.set(false);
    }

    /// Attempts to gracefully shut down a core. Forcefully shuts them down if they time out.
    private void shutdownCore(int coreId, CloneableObject oldClone,
            AtomicInteger drainSignal, AtomicInteger shutDownCounter) {
        this.logger.info("Shutting down clone on core {}", coreId);
        oldClone.setDrainMode(true);

        CompletableFuture.runAsync(() -> {
            Thread.currentThread().setName(this.shardName + "-" + coreId);
            long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
            try {
                while (!oldClone.isDrained()) {
                    LockSupport.parkNanos(5_000);
                    if (System.nanoTime() >= deadline) {
                        this.logger.error("Clone on core {} timed out. Forcing shutdown.", coreId);
                        oldClone.close();
                        break;
                    }
                }
            } catch (Exception e) {
                this.logger.error("Shutdown cleanup failed for Core {}", coreId, e);
            } finally {
                try {
                    oldClone.close();
                } catch (Exception e) {
                    this.logger.error("CRITICAL: Worker on core {} failed to close.", coreId, e);
                    oldClone.dumpLocks();
                } finally {
                    drainSignal.decrementAndGet();
                    if (drainSignal.get() == 0) {
                        shutDownCounter.decrementAndGet();
                    }
                }
            }
        }, this.shardExecutor);
    }

    public boolean isStarted() {
        return this.started.get();
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
    public ControlPlaneShard clone(int shardId, String shardName) {
        return new ControlPlaneShard(shardId, shardName, this.cloneableObject);
    }

    /// Forcefully shuts down all cores.
    @Override
    public void close() throws Exception {
        this.started.set(false);
        this.logger.info("Closing.");
        this.coreDistributor.getAndUpdate(distributor -> {
            if (distributor != null) {
                try {
                    distributor.close();
                } catch (Exception e) {
                    this.logger.error("CRITICAL: Failed to close the LatticeVertex.", e);
                }
            }
            return null;
        });

        this.logger.info("Closing cores.");
        LatticeEdge[] handles = this.coreHandles.getAcquire();
        for (int i = 0; i < handles.length; i++) {
            if (handles[i] != null) {
                handles[i].onComplete();
                handles[i] = null;
            }
        }

        this.logger.info("Closing clones.");
        CloneableObject[] clones = this.clones.getAcquire();
        for (int i = 0; i < clones.length; i++) {
            CloneableObject clone = clones[i];
            if (clone != null) {
                try {
                    clone.close();
                } catch (Exception e) {
                    this.logger.error("Failed to close the clone.", e);
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
