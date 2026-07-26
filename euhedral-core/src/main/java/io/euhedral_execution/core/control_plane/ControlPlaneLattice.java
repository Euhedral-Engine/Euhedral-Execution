package io.euhedral_execution.core.control_plane;

import static io.euhedral_execution.core.utils.MathFunctions.unsignedMultiplyHigh;

import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.generics.LatticeTerminal;
import io.euhedral_execution.core.ingest.AbstractIngestSink;
import io.euhedral_execution.core.internal.Constants;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.ResourceMonitor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.TopologyMapper;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSystemTopology;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SocketSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## Control surface of Euhedral Core
///
/// #### This is where work enters.
///
/// ---
///
/// **Role:**
///
/// `ControlPlaneLattice` is the top-level orchestration and organization layer for the distributed
/// scheduling system.
///
/// It owns system topology, shard placement, and runtime rebalancing. It distributes work across
/// shards and keeps the execution layer aligned with the current hardware state.
///
/// **What it coordinates:**
///
///   - System topology discovery and updates
///   - HardwareUtilization report distribution
///   - Shard lifecycle (create -> start -> rebalance -> shutdown)
///   - CPU/socket-aware routing
///   - Ingest distribution across shards
///   - Global rebalancing under hardware change
///
/// ---
///
/// ### Routing
///
/// Work is routed based on the frame's routingHash and current shard mapping:
///
/// ```java
/// int idx = (int) unsignedMultiplyHigh(frame.getRoutingHash(), map.length);
/// return activeShardIds[idx];
/// ```
///
/// When a routing policy is set, it can override hash-based routing:
///
/// ```java
/// if (policy.level > RoutingPolicy.ANY.level) {
///     return reverseMapping[frame.getOrigin().socket()];
/// }
/// ```
///
/// ---
///
/// ### Behavior
///
/// - Starts lazily on first ingest
/// - Rebalances automatically when topology changes
/// - Spawns and manages per-socket shards
/// - Stays out of the way unless hardware forces a change
///
/// ---
@SuppressWarnings("unused")
public final class ControlPlaneLattice implements LatticeTerminal {

    private static final AtomicReference<ControlPlaneLattice> INSTANCE = new AtomicReference<>();
    private static final VarHandle HANDLES = MethodHandles.arrayElementVarHandle(
            LatticeEdge[].class);

    public static ControlPlaneLattice getOrCreate() {
        return getOrCreate("EuhedralLattice", "EuhedralShard");
    }

    public static ControlPlaneLattice getOrCreate(String name, String shardName) {
        return INSTANCE.updateAndGet(curr -> {
            if (curr != null) {
                return curr;
            }
            return new ControlPlaneLattice(LatticeConfig.ofDefaults(name, shardName));
        });
    }

    public static ControlPlaneLattice getOrCreate(@NonNull LatticeConfig config) {
        Objects.requireNonNull(config);
        return INSTANCE.updateAndGet(curr -> {
            if (curr != null) {
                return curr;
            }
            return new ControlPlaneLattice(config);
        });
    }

    final String name;
    final Logger logger;
    final LatticeConfig config;

    final TopologyMapper topology;
    final ResourceMonitor resourceMonitor;
    final ExecutorService controlPlaneExecutor;
    final Thread shutdownHook;

    final AtomicBoolean closed = new AtomicBoolean(false);
    final AtomicBoolean started = new AtomicBoolean(false);
    final AtomicBoolean ready = new AtomicBoolean(false);
    final AtomicBoolean primed = new AtomicBoolean(false);
    final AtomicBoolean rebalancing = new AtomicBoolean(false);
    final AtomicBoolean resetting = new AtomicBoolean(false);

    final ControlPlaneShard[] shards;
    final LatticeEdge[] shardHandles;
    final AtomicReference<LatticeVertex> ingestController = new AtomicReference<>();

    final AtomicReference<int[]> activeShardIds = new AtomicReference<>(new int[0]);
    final AtomicReference<int[]> weightedShardMap = new AtomicReference<>(new int[0]);

    volatile int currentGlobalVersion = Integer.MIN_VALUE;
    volatile EffectiveSystemTopology effectiveTopology;

    ControlPlaneLattice(LatticeConfig config) {
        this.name =
                config.name() == null || config.name().isBlank() ? this.getClass().getSimpleName()
                        : config.name();
        this.logger = LoggerFactory.getLogger(Constants.getLoggerName(this.name));
        this.config = config;

        this.topology = new TopologyMapper(config.allowedCpus());
        this.resourceMonitor = new ResourceMonitor(this.topology, Duration.ofMillis(200));
        this.controlPlaneExecutor =
                Executors.newFixedThreadPool(SystemInfo.getMaxSocketId() + 1,
                        r -> new Thread(r, this.name));
        this.shutdownHook = new Thread(this::close);

        this.shards = new ControlPlaneShard[SystemInfo.getMaxSocketId() + 1];
        this.shardHandles = new LatticeEdge[this.shards.length];

        this.effectiveTopology = this.topology.getEffectiveTopology();

        this.resourceMonitor.addListener(this::update);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    public void start() {
        if (this.started.compareAndSet(false, true)) {
            this.logger.info("Starting...");
            init();

            HardwareUtilization utilization = this.resourceMonitor.getUtilization();
            this.topology.update(utilization);
            update(utilization);

            this.logger.trace("Awaiting readiness...");
            while (this.rebalancing.getAcquire() || !this.ready()) {
                LockSupport.parkNanos(5_000L);
            }
            this.resourceMonitor.start();
            this.ready.set(true);
            this.logger.info("Startup complete!");
        }
    }

    private boolean ready() {
        int count = this.ingestController.getOpaque().getThreadCount();
        for(ControlPlaneShard shard : this.shards) {
            if(shard != null && !shard.isStarted()) {
                return false;
            }
        }
        return true;
    }

    /// Takes an [AbstractIngestSink] and adds it as a global input source.
    public void addUpstream(@NonNull AbstractIngestSink sink) {
        addUpstream(sink.getDelegate());
    }

    /// Takes a [LatticeSource] and adds it as a global input source.
    public void addUpstream(@NonNull LatticeSource stream) {
        this.logger.trace("Adding upstream...");

        Objects.requireNonNull(stream);
        if (this.closed.getOpaque()) {
            throw new RuntimeException(
                    "Could not ingest from an upstream publisher. The ControlPlaneLattice is permanently closed.");
        }
        if (!this.started.getOpaque()) {
            start();
        }
        while (!this.ready.getOpaque()) {
            LockSupport.parkNanos(5_000);
        }

        LatticeVertex controller = this.ingestController.get();
        controller.ingest(stream);

        this.logger.trace("Added upstream.");
    }

    /// Constructs the [ControlPlaneShards][ControlPlaneShard] and the ingest controller.
    private void init() {
        this.logger.trace("Initializing...");
        for (int i = 0; i < this.shards.length; i++) {
            SocketInfo info = SystemInfo.getSocketInfo(i);
            if (info == null) {
                continue;
            }
            this.shards[i] = this.config.baseShard()
                    .clone(i, this.name, this.config.shutdownTimeout());
            this.logger.info("Created ControlPlaneShard on socket: {}", i);
        }

        LatticeVertex controller = new LatticeVertex(this.name + "-GlobalDistributor",
                SystemInfo.getMaxSocketId() + 1, this::route, 0,
                RoutingPolicy.ANYWHERE);
        this.ingestController.set(controller);
    }

    /// Routes work based on their policy level or uses default global routing.
    private int route(AbstractFrame frame, int mapSize) {
        CpuInfo location = frame.getOrigin();
        RoutingPolicy policy = frame.getRoutingPolicy();
        if (policy.level > RoutingPolicy.ANYWHERE.level && location != null) {
            int socket = location.socket();
            LatticeEdge edge = (LatticeEdge) HANDLES.getAcquire(this.shardHandles, socket);
            if (edge != null) {
                return socket;
            }
        }

        // Default routing
        return (int) unsignedMultiplyHigh(frame.getRoutingHash(), mapSize);
    }

    /// Hands out the shard-specific hardware utilization reports or initiates a rebalance on
    /// topology change.
    void update(HardwareUtilization utilization) {
        if (this.resetting.getAcquire()) {
            return;
        }
        int nextVersion = this.topology.getGlobalVersion();

        if (this.currentGlobalVersion != nextVersion) {
            if (!this.primed.getOpaque()) {
                this.logger.info("Initializing the ControlPlaneLattice for global topology V{}",
                        nextVersion);
            } else {
                this.logger.warn(
                        "Detected change in global topology. Initiating global rebalance for topology V{}",
                        nextVersion);
            }
            handleSystemTopologyChange(utilization);
            if (this.effectiveTopology.effectiveCpus().cardinality() == 0) {
                logger.error("There are no usable cpus for this ControlPlaneLattice.");
            }
        } else {
            double quotaPool = utilization.quotaCpus();

            int[] sockets = this.activeShardIds.getOpaque();
            for (int socketId : sockets) {
                EffectiveSocketTopology topology =
                        this.effectiveTopology.socketTopologies().get(socketId);
                ControlPlaneShard shard = this.shards[socketId];

                SocketSnapshot snapshot =
                        utilization.getSocketSnapshot(socketId, topology.effectiveCoreToCpu(),
                                getShardQuota(socketId, quotaPool));
                CompletableFuture.runAsync(() -> {
                    if (!shard.isStarted()) {
                        this.logger.info("Starting shard");
                        startShard(socketId, snapshot, topology);
                    } else {
                        shard.update(snapshot, topology);
                    }
                }, this.controlPlaneExecutor);
            }
        }
    }

    /// Spawns new shards if a socket becomes available. Shuts down shards if the socket is removed.
    /// Updates existing shards with their new utilization reports.
    private void handleSystemTopologyChange(HardwareUtilization utilization) {
        if (!this.rebalancing.compareAndSet(false, true)) {
            return;
        }
        this.effectiveTopology = this.topology.getEffectiveTopology();
        this.currentGlobalVersion = this.topology.getGlobalVersion();

        LatticeVertex controller = this.ingestController.get();

        BitSet newShards = this.effectiveTopology.effectiveSockets();
        for (int socket = newShards.nextSetBit(0); socket >= 0;
                socket = newShards.nextSetBit(socket + 1)) {
            if (this.shardHandles[socket] == null) {
                HANDLES.setRelease(this.shardHandles, socket,
                        new LatticeEdge(controller.getDrainFlag()));
            }
        }
        AtomicInteger shutDown = new AtomicInteger(0);
        for (int i = 0; i < this.shardHandles.length; i++) {
            if (!newShards.get(i) && this.shardHandles[i] != null) {
                HANDLES.setRelease(this.shardHandles, i, null);
                shutDown.incrementAndGet();
            }
        }

        remapIngestController();

        // Divide the quota proportionally based on cpu count
        double quotaPool = utilization.quotaCpus();
        this.logger.trace("Dividing quota pool of {} quota cpus", quotaPool);
        for (int socket = newShards.nextSetBit(0); socket >= 0;
                socket = newShards.nextSetBit(socket + 1)) {
            if (this.shards[socket] == null) {
                continue;
            }

            EffectiveSocketTopology topology =
                    this.effectiveTopology.socketTopologies().get(socket);

            double shardQuota = getShardQuota(socket, quotaPool);
            SocketSnapshot snapshot =
                    utilization.getSocketSnapshot(socket, topology.effectiveCoreToCpu(),
                            shardQuota);

            this.logger.trace("Shard {} has been allocated {} quota cpus", socket, shardQuota);
            if (!this.shards[socket].isStarted()) {
                startShard(socket, snapshot, topology);
            } else {
                this.shards[socket].update(snapshot, topology);
            }
        }

        int idx = 0;
        int[] nextSockets = new int[newShards.cardinality()];

        for (int i = newShards.nextSetBit(0); i >= 0; i = newShards.nextSetBit(i + 1)) {
            nextSockets[idx++] = i;
        }

        this.activeShardIds.lazySet(nextSockets);

        this.ingestController.get().setDrain(false);
        if (!this.primed.getOpaque()) {
            this.primed.lazySet(true);
            this.rebalancing.lazySet(false);
            return;
        }

        // Shutdown decommissioned shards and restart ingest on complete.
        if (shutDown.getPlain() > 0) {
            this.logger.info("Shutting down {} old shards.", shutDown.getPlain());
            CompletableFuture.runAsync(() -> {
                for (int i = 0; i < this.shards.length; i++) {
                    if (!newShards.get(i)) {
                        shutDown.decrementAndGet();
                        this.shards[i].shutDownShard(shutDown);
                    }
                }
                while (shutDown.get() != 0) {
                    LockSupport.parkNanos(1_000);
                }
                this.rebalancing.lazySet(false);
            }, this.controlPlaneExecutor);
        } else {
            this.rebalancing.lazySet(false);
        }
    }

    /// Cuts ingest by setting the ingest controller to drain mode and changes the mappings to the
    /// next shards. Does not reactivate ingest in here.
    private void remapIngestController() {
        this.logger.trace("Remapping ingest controller.");
        this.ingestController.get().setDrain(true);
        BitSet effectiveSockets = this.effectiveTopology.effectiveSockets();
        BitSet effectiveCpus = this.effectiveTopology.effectiveCpus();

        this.logger.trace("Building shard map weighted by cpu count.");
        int idx = 0;
        int[] weightedShardMap = new int[this.effectiveTopology.effectiveCpus().cardinality()];
        for (int i = effectiveCpus.nextSetBit(0); i >= 0; i = effectiveCpus.nextSetBit(i + 1)) {
            weightedShardMap[idx++] = SystemInfo.getCpuInfo(i).socket();
        }

        LatticeVertex controller = this.ingestController.get();
        long deadline = System.nanoTime() + this.config.shutdownTimeout().toNanos();
        while (!controller.setDownstreamMapping(effectiveSockets, this.shardHandles)) {
            LockSupport.parkNanos(5_000);
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        this.weightedShardMap.lazySet(weightedShardMap);
    }

    private void startShard(int shardId, SocketSnapshot snapshot,
            EffectiveSocketTopology topology) {
        if (this.shards[shardId].isStarted()) {
            return;
        }

        this.shards[shardId].start(snapshot, topology, this.shardHandles[shardId]);
    }

    /// Calculates the proportional quota for a shard based on their CPU count.
    private double getShardQuota(int socketId, double systemQuotaPool) {
        int totalEffectiveCpus = this.effectiveTopology.effectiveCpus().cardinality();
        int socketEffectiveCpus =
                this.effectiveTopology.socketTopologies().get(socketId).effectiveCpus()
                        .cardinality();

        return ((double) socketEffectiveCpus / Math.max(1, totalEffectiveCpus)) * systemQuotaPool;
    }

    /**
     * Freezes ingest and clears all socket-distributor and fragment-local caches before another
     * benchmark policy is activated. Ingest sources should be paused before calling this method.
     */
    public CacheReset resetForNextTrial(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Reset timeout must be positive");
        }
        if (this.closed.getAcquire()) {
            throw new IllegalStateException("Cannot reset a closed ControlPlaneLattice");
        }
        if (!this.started.getAcquire()) {
            return new CacheReset(0, 0);
        }
        if (!this.resetting.compareAndSet(false, true)) {
            throw new IllegalStateException("A lattice reset is already in progress");
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        LatticeVertex controller = this.ingestController.getAcquire();
        try {
            while (this.rebalancing.getAcquire() && System.nanoTime() < deadline) {
                LockSupport.parkNanos(5_000L);
            }
            if (this.rebalancing.getAcquire()) {
                throw new IllegalStateException(
                        "Timed out waiting for global rebalance before trial reset");
            }

            if (controller != null) {
                controller.setDrain(true);
            }
            long cleared = controller == null ? 0 : controller.clearCachedFrames();
            for (ControlPlaneShard shard : this.shards) {
                if (shard != null) {
                    cleared += shard.resetForNextTrial(deadline);
                }
            }
            return new CacheReset(cleared, getActiveWorkers());
        } finally {
            if (controller != null) {
                controller.setDrain(false);
            }
            this.resetting.setRelease(false);
        }
    }

    public int getActiveWorkers() {
        int count = 0;
        for (ControlPlaneShard shard : this.shards) {
            if (shard != null) {
                count += shard.getActiveCores();
            }
        }
        return count;
    }

    /// Permanently shuts down the ControlPlaneLattice.
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        logger.info("Closing.");
        LatticeVertex controller = this.ingestController.getAndSet(null);

        try {
            if (controller != null) {
                controller.close();
            }
        } catch (Exception e) {
            this.logger.error("Error closing ControlPlaneIngestController.", e);
        }

        this.resourceMonitor.close();
        PinnedThreadExecutor.closeAll();
        for (int i = 0; i < this.shards.length; i++) {
            if (this.shards[i] != null) {
                try {
                    this.shardHandles[i] = null;
                    this.shards[i].close();
                } catch (Exception e) {
                    this.logger.error("Error closing shard {}", this.shards[i].getShardName(), e);
                } finally {
                    this.shards[i] = null;
                }
            }
        }
        this.activeShardIds.set(null);

        INSTANCE.set(null);

        try {
            this.controlPlaneExecutor.shutdownNow();
            Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
        } catch (Exception ignored) {
            // Executor pool errors can be ignored on shutdown.
        }
        this.logger.info("Closed.");
    }

    public record CacheReset(long clearedFrames, int activeWorkers) {
    }

    /// Whether all queues are empty and all in-progress work is completed for all CPUs managed by
    /// this ControlPlaneLattice.
    public boolean isDrained() {
        LatticeVertex controller = this.ingestController.get();
        if (controller != null && !controller.isDrained()) {
            return false;
        }

        for (ControlPlaneShard shard : this.shards) {
            if (shard != null && !shard.isDrained()) {
                return false;
            }
        }
        return true;
    }
}
