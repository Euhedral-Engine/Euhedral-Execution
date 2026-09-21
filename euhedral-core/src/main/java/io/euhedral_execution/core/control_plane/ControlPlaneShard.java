package io.euhedral_execution.core.control_plane;

import static io.euhedral_execution.core.utils.MathFunctions.unsignedMultiplyHigh;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.CloneLivenessRegistry;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.internal.Constants;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.data_structures.queues.PlainQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SocketSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
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

    protected static final VarHandle HANDLE = MethodHandles.arrayElementVarHandle(LatticeEdge[].class);
    protected final Logger logger;
    protected final Duration shutdownTimeout;

    @Getter
    protected final int socket;

    @Getter
    protected final String shardName;

    protected final AtomicLong currentVersion = new AtomicLong(Integer.MIN_VALUE);
    protected final AtomicBoolean primed = new AtomicBoolean(false);
    protected final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean shutdownScheduled = new AtomicBoolean(false);
    protected final AtomicBoolean rebalancing = new AtomicBoolean(false);
    protected final AtomicInteger coresToDrain = new AtomicInteger(0);
    protected final AtomicReference<LatticeVertex> coreDistributor = new AtomicReference<>();
    protected final CloneableObject cloneableObject;
    protected final AtomicReference<int[]> activeCoreIds = new AtomicReference<>(new int[0]);
    protected final AtomicReference<CloneableObject[]> clones = new AtomicReference<>(new CloneableObject[0]);
    protected final LatticeEdge[] coreHandles = new LatticeEdge[SystemInfo.getMaxCoreId() + 1];
    protected volatile ExecutorService shardExecutor;
    private LatticeEdge upstreamEdge;
    // Serializes final-drain publication with generation retirement, never frame processing.
    private final Object lifecycleLock = new Object();

    protected ControlPlaneShard(int socket, String shardName, CloneableObject obj, Duration shutdownTimeout) {
        this.logger = LoggerFactory.getLogger(Constants.getLoggerName(shardName));
        this.shutdownTimeout = shutdownTimeout;
        this.socket = socket;
        this.shardName = shardName;
        this.cloneableObject = obj;
    }

    public static ControlPlaneShard createBaseShard(@NonNull CloneableObject cloneableObject) {
        Objects.requireNonNull(cloneableObject);
        return new ControlPlaneShard(-1, ControlPlaneShard.class.getSimpleName(), cloneableObject, Duration.ZERO);
    }

    public static ControlPlaneShard createBaseShard(String name, @NonNull CloneableObject cloneableObject) {
        Objects.requireNonNull(cloneableObject);
        return new ControlPlaneShard(-1, name, cloneableObject, Duration.ZERO);
    }

    public int getActiveCores() {
        return this.activeCoreIds.getAcquire().length;
    }

    public void start(SocketSnapshot snapshot, EffectiveSocketTopology topology, LatticeEdge upstream) {
        LatticeVertex coreDistributor;
        synchronized (this.lifecycleLock) {
            if (!this.started.compareAndSet(false, true)) {
                return;
            }
            this.shutdownScheduled.set(false);
            this.upstreamEdge = upstream;
            this.logger.info("Starting.");
            this.shardExecutor = Executors.newFixedThreadPool(
                    topology.effectiveCores().length(), r -> new Thread(r, this.shardName + "-ExecutorService"));

            SocketInfo info = SystemInfo.getSocketInfo(snapshot.socketId());
            BitSet coreSet = info.getCoreSet();

            coreDistributor = new LatticeVertex(
                    this.shardName + "-CoreDistributor",
                    coreSet.previousSetBit(coreSet.length()) + 1,
                    (frame, mapSize, state) -> this.route(frame, mapSize, state));
            this.coreDistributor.set(coreDistributor);
        }
        coreDistributor.addUpstream(upstream);
        update(snapshot, topology);
    }

    /// Routes work based on their policy level or uses default global routing.
    protected int route(AbstractFrame frame, int mapSize) {
        return route(frame, mapSize, null);
    }

    protected int route(AbstractFrame frame, int mapSize, LatticeVertex.RoutingState routeState) {
        RoutingPolicy policy = frame.getRoutingPolicy();
        CpuInfo location = frame.getOrigin();
        if (policy.level > RoutingPolicy.SOCKET_LOCAL.level && location != null) {
            int core = location.core();
            LatticeEdge handle;
            int index;
            if (routeState != null) {
                handle = routeState.getDownstream(core);
                index = routeState.getActiveDownstreamIndex(core);
            } else {
                handle = core >= 0 && core < this.coreHandles.length
                        ? (LatticeEdge) HANDLE.getOpaque(this.coreHandles, core)
                        : null;
                index = this.coreDistributor.get().getActiveDownstreamIndex(core);
            }
            if (handle != null && index >= 0) {
                return index;
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
            this.logger.warn("Cannot update while rebalancing. CoresToDrain: {}", this.coresToDrain.get());
            return;
        }

        int nextVersion = topology.version();
        if (!this.primed.getOpaque()) {
            this.logger.info("Priming clones for socket topology V{}", nextVersion);
            handleTopologyChange(snapshot, topology);
        } else if (this.currentVersion.getAcquire() != nextVersion) {
            this.logger.warn("Detected change in topology. Initiating rebalance for socket topology V{}", nextVersion);
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
        prepareIngestController(newCores);

        CloneableObject[] oldClones = this.clones.getPlain();
        createNextClones(snapshot, topology);
        publishIngestController(newCores);

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
        prepareIngestController(newCores);
        publishIngestController(newCores);
    }

    private void prepareIngestController(BitSet newCores) {
        this.logger.trace("Preparing ingest controller remap.");
        LatticeVertex distributor = this.coreDistributor.get();
        distributor.setDrain(true);
        for (int i = newCores.nextSetBit(0); i >= 0; i = newCores.nextSetBit(i + 1)) {
            LatticeEdge handle = (LatticeEdge) HANDLE.getOpaque(this.coreHandles, i);
            if (handle == null) {
                handle = new LatticeEdge(distributor.getDrainFlag());
                HANDLE.setRelease(this.coreHandles, i, handle);
            }
        }
    }

    private void publishIngestController(BitSet newCores) {
        this.logger.trace("Publishing ingest controller remap.");
        LatticeVertex distributor = this.coreDistributor.get();
        if (!distributor.setDownstreamMapping(newCores, this.coreHandles)) {
            throw new IllegalStateException("Core distributor rejected a prepared remap");
        }
    }

    protected void createNextClones(SocketSnapshot snapshot, EffectiveSocketTopology topology) {
        this.logger.info("Creating new clones");
        CloneableObject[] clones = this.clones.getOpaque();
        BitSet newCores = topology.effectiveCores();

        int idx = 0;
        int[] nextCores = new int[newCores.cardinality()];
        CloneableObject[] nextClones =
                new CloneableObject[topology.effectiveCores().length()];
        for (int i = newCores.nextSetBit(0); i >= 0; i = newCores.nextSetBit(i + 1)) {
            if (i >= clones.length || clones[i] == null) {
                nextClones[i] = spawnClone(i, snapshot.coreSnapshots()[i], nextClones);
            } else {
                nextClones[i] = clones[i];
                final int id = i;
                CompletableFuture.runAsync(
                        () -> updateClone(clones[id], snapshot.coreSnapshots()[id]), this.shardExecutor);
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
    protected CloneableObject spawnClone(int coreId, CoreSnapshot snapshot, CloneableObject[] nextClones) {
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
        LatticeVertex expectedDistributor = this.coreDistributor.get();
        LatticeEdge[] retiringHandles = Arrays.copyOf(this.coreHandles, this.coreHandles.length);

        Set<Integer> deadClones = new HashSet<>();
        PlainQueue<CloneableObject> clones = new PlainQueue<>(Math.max(2, oldClones.length));
        for (int core = 0; core < oldClones.length; core++) {
            CloneableObject clone = oldClones[core];
            if (clone == null) {
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

        clones.drain(clone -> CompletableFuture.runAsync(
                () -> {
                    int core = clone.getCore();

                    Thread.currentThread().setName(this.shardName + "-" + core);
                    final long deadline = System.nanoTime() + this.shutdownTimeout.toNanos();

                    SpinWait.awaitWhile(() -> !clone.isDrained() && System.nanoTime() < deadline);

                    boolean close = deadClones.contains(core) || System.nanoTime() >= deadline;
                    synchronized (this.lifecycleLock) {
                        // Retirement settles its count; late tasks cannot replace edges or spawn into the next run.
                        if (!this.started.get() || this.coreDistributor.get() != expectedDistributor) {
                            if (close) {
                                closeClone(clone, deadline);
                            }
                            return;
                        }
                        if (close && !deadClones.contains(core)) {
                            this.logger.info("Restarting clone on core {}", core);
                            HANDLE.setRelease(
                                    this.coreHandles, core, new LatticeEdge(expectedDistributor.getDrainFlag()));
                            spawnClone(core, snapshot.coreSnapshots()[core], currClones);
                        }
                        if (close) {
                            // The replacement mapping retires this generation. Its direct edge and
                            // receiver owner stay live until every route admitted to it returns.
                            LatticeEdge retiringHandle = retiringHandles[core];
                            if (retiringHandle == null) {
                                closeClone(clone, deadline);
                            } else {
                                retiringHandle.deferRetirement(() -> closeClone(clone, deadline));
                            }
                        }
                        int remaining = this.coresToDrain.decrementAndGet();
                        if (remaining == 0) {
                            this.logger.info("Drain complete.");
                            tryRestartIngest();
                        }
                    }
                },
                this.shardExecutor));
    }

    protected final void closeClone(CloneableObject clone, long deadlineNanos) {
        try {
            clone.close();
        } catch (Exception e) {
            this.logger.error("Failed to shut down clone on core {}", clone.getCore());
        } finally {
            awaitCloneTermination(clone, deadlineNanos);
            clone.dumpLocks();
        }
    }

    private void awaitCloneTermination(CloneableObject clone, long deadlineNanos) {
        CloneLivenessRegistry registry = clone.livenessRegistry();
        if (registry == null) {
            return;
        }
        if (!registry.awaitTermination(deadlineNanos)) {
            this.logger.warn("Timed out waiting for clone workers on core {}; continuing shutdown", clone.getCore());
        }
    }

    /// Restarts ingest if all cores are drained.
    protected void tryRestartIngest() {
        synchronized (this.lifecycleLock) {
            if (!this.started.get() || this.coreDistributor.get() == null) {
                return;
            }
            if (this.coresToDrain.get() == 0) {
                this.logger.info("Restarting ingest.");
                // The last drain task verifies the complete replacement map before reopening ingest.
                LatticeVertex distributor = this.coreDistributor.get();
                BitSet active = new BitSet(this.coreHandles.length);
                for (int core : this.activeCoreIds.getAcquire()) {
                    active.set(core);
                }
                if (!distributor.setDownstreamMapping(active, this.coreHandles)) {
                    throw new IllegalStateException("Core distributor rejected the restart mapping");
                }
                for (CloneableObject clone : this.clones.getAcquire()) {
                    if (clone != null) {
                        clone.setDrainMode(false);
                    }
                }
                distributor.setDrain(active.isEmpty());
                this.rebalancing.set(false);
            }
        }
    }

    /// Shuts down all cores under the shard's control after its incoming route generations retire.
    public void shutDownShard(AtomicInteger shutDownCounter) {
        LatticeEdge incoming;
        LatticeVertex expectedDistributor;
        synchronized (this.lifecycleLock) {
            if (!this.started.get() || !this.shutdownScheduled.compareAndSet(false, true)) {
                shutDownCounter.decrementAndGet();
                return;
            }
            incoming = this.upstreamEdge;
            expectedDistributor = this.coreDistributor.get();
        }

        Runnable shutdown = () -> shutDownShardNow(shutDownCounter, expectedDistributor);
        if (incoming == null) {
            shutdown.run();
        } else {
            incoming.deferRetirement(shutdown);
        }
    }

    private void shutDownShardNow(AtomicInteger shutDownCounter, LatticeVertex expectedDistributor) {
        CloneableObject[] clones;
        LatticeVertex distributor;
        ExecutorService executor;
        synchronized (this.lifecycleLock) {
            if (this.coreDistributor.get() != expectedDistributor || !this.started.compareAndSet(true, false)) {
                shutDownCounter.decrementAndGet();
                return;
            }
            clones = this.clones.getAndSet(new CloneableObject[0]);
            distributor = this.coreDistributor.getAndSet(null);
            executor = this.shardExecutor;
            this.shardExecutor = null;
            this.upstreamEdge = null;
            this.activeCoreIds.setRelease(new int[0]);
            Arrays.fill(this.coreHandles, null);
            this.primed.set(false);
            this.coresToDrain.set(0);
            this.rebalancing.set(false);
        }
        this.logger.info("Shutting down...");

        int cloneCount = 0;
        for (CloneableObject clone : clones) {
            if (clone != null) {
                cloneCount++;
            }
        }
        AtomicInteger drainCounter = new AtomicInteger(cloneCount);
        long deadline = shutdownDeadline();
        for (int i = 0; i < clones.length; i++) {
            if (clones[i] == null) {
                continue;
            }

            CloneableObject clone = clones[i];
            clones[i] = null;

            shutdownCore(i, clone, drainCounter, shutDownCounter, executor, deadline);
        }
        if (cloneCount == 0) {
            shutDownCounter.decrementAndGet();
        }
        if (distributor != null) {
            distributor.close();
        }
        executor.shutdown();
        this.logger.info("Shutdown complete.");
    }

    /// Attempts to gracefully shut down a core. Forcefully shuts them down if they time out.
    private void shutdownCore(
            int coreId,
            CloneableObject oldClone,
            AtomicInteger drainSignal,
            AtomicInteger shutDownCounter,
            ExecutorService executor,
            long deadline) {
        this.logger.trace("Shutting down clone on core {}", coreId);
        oldClone.setDrainMode(true);

        CompletableFuture.runAsync(
                () -> {
                    Thread.currentThread().setName(this.shardName + "-" + coreId);
                    try {
                        SpinWait.awaitWhile(() -> !oldClone.isDrained() && System.nanoTime() < deadline);
                        if (!oldClone.isDrained() && System.nanoTime() >= deadline) {
                            this.logger.error("Clone on core {} timed out. Forcing shutdown.", coreId);
                        }
                    } catch (Exception e) {
                        this.logger.error("Shutdown cleanup failed for Core {}", coreId, e);
                    } finally {
                        try {
                            closeClone(oldClone, deadline);
                        } catch (Exception e) {
                            this.logger.error("CRITICAL: Worker on core {} failed to close.", coreId, e);
                        } finally {
                            if (drainSignal.decrementAndGet() == 0) {
                                shutDownCounter.decrementAndGet();
                            }
                        }
                    }
                },
                executor);
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

        try {
            long cleared = 0;
            for (CloneableObject clone : activeClones) {
                if (clone != null) {
                    cleared += clone.reset(deadlineNanos);
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
        if (!this.started.getAcquire()) {
            return false;
        }
        CloneableObject[] clones = this.clones.getAcquire();
        for (var clone : clones) {
            if (clone != null && !clone.ready()) {
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
        return new ControlPlaneShard(
                socketId, rootName + "-" + this.shardName + "-" + socketId, this.cloneableObject, shutdownTimeout);
    }

    /// Forcefully shuts down all cores.
    public void close() {
        LatticeVertex distributor;
        CloneableObject[] clones;
        ExecutorService executor;
        synchronized (this.lifecycleLock) {
            this.started.set(false);
            distributor = this.coreDistributor.getAndSet(null);
            clones = this.clones.getAndSet(new CloneableObject[0]);
            executor = this.shardExecutor;
            this.shardExecutor = null;
            this.upstreamEdge = null;
            this.activeCoreIds.setRelease(new int[0]);
            Arrays.fill(this.coreHandles, null);
            this.primed.set(false);
            this.coresToDrain.set(0);
            this.rebalancing.set(false);
        }
        this.logger.info("Closing.");
        long deadline = shutdownDeadline();
        if (distributor != null) {
            try {
                distributor.close();
            } catch (Exception e) {
                this.logger.error("CRITICAL: Failed to close the ingest controller.", e);
            }
        }
        for (int i = 0; i < clones.length; i++) {
            CloneableObject clone = clones[i];
            if (clone != null) {
                try {
                    closeClone(clone, deadline);
                } catch (Exception e) {
                    this.logger.error("Failed to close clone.", e);
                }
                clones[i] = null;
            }
        }

        if (executor != null) {
            executor.shutdownNow();
        }
        this.logger.info("Closed.");
    }

    private long shutdownDeadline() {
        long timeoutNanos = this.shutdownTimeout.toNanos();
        return timeoutNanos <= 0L ? System.nanoTime() : System.nanoTime() + timeoutNanos;
    }
}
