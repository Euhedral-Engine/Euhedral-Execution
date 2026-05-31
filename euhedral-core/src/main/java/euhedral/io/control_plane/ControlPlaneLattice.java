package euhedral.io.control_plane;

import static euhedral.io.utils.MathFunctions.unsignedMultiplyHigh;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.ResourceMonitor;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hardware_utils.SystemInfo.SocketInfo;
import euhedral.hardware_utils.TopologyMapper;
import euhedral.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import euhedral.hardware_utils.TopologyMapper.EffectiveSystemTopology;
import euhedral.hardware_utils.common.SystemUtilization.HardwareUtilization;
import euhedral.hardware_utils.common.SystemUtilization.SocketSnapshot;
import euhedral.io.config.ControlPlaneConfig;
import euhedral.io.flow_control.LatticeEdge;
import euhedral.io.flow_control.LatticeVertex;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.CloneableObject;
import euhedral.io.generics.LatticeSource;
import euhedral.io.impl.DefaultCloneablePipeline;
import euhedral.io.ingest.IngestSink;
import java.time.Duration;
import java.util.Arrays;
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
///`ControlPlaneLattice` is the top-level orchestration and organization layer for the distributed
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
///
/// **This is the thing above the thing above the things.** Everything starts from here.
@SuppressWarnings("unused")
public class ControlPlaneLattice implements AutoCloseable {

    private static final AtomicReference<ControlPlaneLattice> INSTANCE = new AtomicReference<>();

    public static ControlPlaneLattice get() {
        return INSTANCE.get();
    }

    public static ControlPlaneLattice getOrCreate(String name) {
        return INSTANCE.updateAndGet(curr -> {
            if (curr != null) {
                return curr;
            }

            return new ControlPlaneLattice(name, null, new DefaultCloneablePipeline(),
                    SystemInfo.getCpuSet());
        });
    }

    public static ControlPlaneLattice getOrCreate(@NonNull ControlPlaneConfig config) {
        Objects.requireNonNull(config);
        return INSTANCE.updateAndGet(curr -> {
            if (curr != null) {
                return curr;
            }
            BitSet allowedCpus = config.allowedCpus();
            CloneableObject cloneable = config.cloneableObject();
            if (allowedCpus == null) {
                allowedCpus = SystemInfo.getCpuSet();
            }
            if (cloneable == null) {
                cloneable = new DefaultCloneablePipeline(config.metricPrefix(),
                        config.meterRegistry());
            }
            return new ControlPlaneLattice(config.name(), config.baseShard(), cloneable,
                    allowedCpus);
        });
    }

    protected final String name;
    protected final TopologyMapper topology;
    protected final ResourceMonitor resourceMonitor;
    protected final Logger logger;
    protected final ExecutorService controlPlaneExecutor;
    protected final AtomicBoolean closed = new AtomicBoolean(false);
    protected final Thread shutdownHook;

    protected final AtomicBoolean started = new AtomicBoolean(false);
    protected final AtomicBoolean ready = new AtomicBoolean(false);
    protected final AtomicBoolean primed = new AtomicBoolean(false);
    protected final AtomicBoolean rebalancing = new AtomicBoolean(false);
    protected final AtomicReference<LatticeVertex> ingestController;

    protected final ControlPlaneShard baseShard;
    protected final ControlPlaneShard[] shards;
    protected final LatticeEdge[] shardHandles;

    protected final BitSet allowedCores;
    protected final AtomicReference<int[]> activeShardIds = new AtomicReference<>(new int[0]);
    protected final AtomicReference<int[]> weightedShardMap = new AtomicReference<>(new int[0]);
    protected final AtomicReference<int[]> reverseMapping = new AtomicReference<>(new int[0]);

    protected volatile int currentGlobalVersion = Integer.MIN_VALUE;
    protected volatile EffectiveSystemTopology effectiveTopology;

    protected ControlPlaneLattice(String name, ControlPlaneShard baseShard,
            CloneableObject cloneableObject, BitSet allowedCpus) {
        this.topology = new TopologyMapper(allowedCpus);
        this.resourceMonitor = new ResourceMonitor(this.topology, Duration.ofMillis(200));

        this.baseShard = Objects.requireNonNullElseGet(baseShard,
                () -> new ControlPlaneShard(-1, "BaseShard", cloneableObject));

        this.name = name == null || name.isBlank() ? this.getClass().getSimpleName() : name;
        this.logger = LoggerFactory.getLogger(name);
        this.effectiveTopology = this.topology.getEffectiveTopology();
        this.ingestController = new AtomicReference<>();
        this.shards = new ControlPlaneShard[SystemInfo.getMaxSocketId() + 1];
        this.shardHandles = new LatticeEdge[SystemInfo.getMaxSocketId() + 1];

        this.allowedCores = allowedCpus;
        this.controlPlaneExecutor =
                Executors.newFixedThreadPool(this.shards.length, r -> new Thread(r, this.name));

        this.shutdownHook = new Thread(this::close);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    public void start() {
        if (this.started.compareAndSet(false, true)) {
            this.resourceMonitor.start();

            init();
            this.topology.update(this.resourceMonitor.getUtilization());

            update(resourceMonitor.getUtilization());
            this.resourceMonitor.addListener(this::update);
            while (this.rebalancing.get() || !this.ready()) {
                LockSupport.parkNanos(1_000L);
            }
            this.ready.setRelease(true);
        }
    }

    /// Takes an [IngestSink] and adds it as a global input source.
    public void ingest(@NonNull IngestSink sink) {
        ingest(sink.getDelegate());
    }

    /// Takes a [LatticeSource] and adds it as a global input source.
    public void ingest(@NonNull LatticeSource stream) {
        Objects.requireNonNull(stream);
        if (this.closed.getOpaque()) {
            throw new RuntimeException(
                    "Could not ingest from an upstream publisher. The ControlPlaneLattice is permanently closed.");
        }
        if (!this.started.getOpaque()) {
            start();
        }
        while (!this.ready.getOpaque()) {
            LockSupport.parkNanos(1_000);
        }

        LatticeVertex controller = this.ingestController.get();
        controller.ingest(stream);
    }

    /// Constructs the [ControlPlaneShards] and the ingest controller.
    protected void init() {
        this.logger.info("Initializing");

        for (int i = 0; i < this.shards.length; i++) {
            SocketInfo info = SystemInfo.getSocketInfo(i);
            if (info == null) {
                continue;
            }
            this.shards[i] = createShard(i);
            this.logger.info("Created ControlPlaneShard on socket: {}", i);
        }

        LatticeVertex controller = new LatticeVertex(this.name + "-GlobalDistributor",
                this.effectiveTopology.socketTopologies().size(), this::route, false);
        this.ingestController.set(controller);
    }

    protected ControlPlaneShard createShard(int socketId) {
        String shardName = this.name + "-ControlPlaneShard-" + socketId;
        return this.baseShard.clone(socketId, shardName);
    }

    /// Routes work based on their policy level or uses default global routing.
    protected int route(AbstractFrame frame, int mapSize) {
        RoutingPolicy policy = frame.getRoutingPolicy();
        if (policy != null && policy.level > RoutingPolicy.ANY.level) {
            int[] reverseMapping = this.reverseMapping.getOpaque();
            CpuInfo location = frame.getOrigin();

            int socket = location != null ? location.socket() : -1;
            if (socket >= 0 && socket < reverseMapping.length
                    && (socket = reverseMapping[socket]) < mapSize && socket >= 0) {
                return socket;
            }
        }

        // Default routing
        return (int) unsignedMultiplyHigh(frame.getRoutingHash(), mapSize);
    }

    /// Hands out the shard-specific hardware utilization reports or initiates a rebalance on
    /// topology change.
    protected void update(HardwareUtilization utilization) {
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
                        this.logger.trace("Starting shard");
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
    protected void handleSystemTopologyChange(HardwareUtilization utilization) {
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
                this.shardHandles[socket] = new LatticeEdge(controller.getDrainFlag());
            }
        }
        remapIngestController();

        // Divide the quota proportionally based on cpu count
        double quotaPool = utilization.quotaCpus();
        for (int socket = newShards.nextSetBit(0); socket >= 0;
                socket = newShards.nextSetBit(socket + 1)) {
            if (this.shards[socket] == null) {
                continue;
            }

            EffectiveSocketTopology topology =
                    this.effectiveTopology.socketTopologies().get(socket);
            SocketSnapshot snapshot =
                    utilization.getSocketSnapshot(socket, topology.effectiveCoreToCpu(),
                            getShardQuota(socket, quotaPool));

            if (!this.shards[socket].isStarted()) {
                startShard(socket, snapshot, topology);
            } else {
                this.shards[socket].update(snapshot, topology);
            }
        }

        int idx = 0;
        int[] nextSockets = new int[newShards.cardinality()];
        int[] reverseMapping = new int[SystemInfo.getMaxSocketId() + 1];
        Arrays.fill(reverseMapping, -1);

        // Build the reverse mapping for fast lookups on socket-local policies
        for (int i = newShards.nextSetBit(0); i >= 0; i = newShards.nextSetBit(i + 1)) {
            reverseMapping[i] = idx;
            nextSockets[idx++] = i;
        }

        AtomicInteger shutDown = new AtomicInteger(0);
        for (int i : this.activeShardIds.getOpaque()) {
            if (!newShards.get(i)) {
                shutDown.incrementAndGet();
            }
        }

        this.activeShardIds.lazySet(nextSockets);
        this.reverseMapping.lazySet(reverseMapping);

        this.ingestController.get().setDrain(false);
        if (!this.primed.getOpaque()) {
            this.primed.lazySet(true);
            this.rebalancing.lazySet(false);
            return;
        }

        // Shutdown decommissioned shards and restart ingest on complete.
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
    }

    /// Cuts ingest by setting the ingest controller to drain mode and changes the mappings to the
    /// next shards. Does not reactivate ingest in here.
    protected void remapIngestController() {
        this.ingestController.get().setDrain(true);
        BitSet effectiveSockets = this.effectiveTopology.effectiveSockets();
        BitSet effectiveCpus = this.effectiveTopology.effectiveCpus();

        int idx = 0;
        int[] weightedShardMap = new int[this.effectiveTopology.effectiveCpus().cardinality()];
        for (int i = effectiveCpus.nextSetBit(0); i >= 0; i = effectiveCpus.nextSetBit(i + 1)) {
            weightedShardMap[idx++] = SystemInfo.getCpuInfo(i).socket();
        }

        LatticeVertex controller = this.ingestController.get();
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!controller.setDownstreamMapping(effectiveSockets, this.shardHandles)) {
            LockSupport.parkNanos(5_000);
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        this.weightedShardMap.lazySet(weightedShardMap);
    }

    protected void startShard(int shardId, SocketSnapshot snapshot,
            EffectiveSocketTopology topology) {
        if (this.shards[shardId].isStarted()) {
            return;
        }

        this.shards[shardId].start(snapshot, topology, this.shardHandles[shardId]);
    }

    /// Calculates the proportional quota for a shard based on their CPU count.
    protected double getShardQuota(int socketId, double systemQuotaPool) {
        int totalEffectiveCpus = this.effectiveTopology.effectiveCpus().cardinality();
        int socketEffectiveCpus =
                this.effectiveTopology.socketTopologies().get(socketId).effectiveCpus()
                        .cardinality();

        return ((double) socketEffectiveCpus / Math.max(1, totalEffectiveCpus)) * systemQuotaPool;
    }

    public boolean ready() {
        while (true) {
            int count = this.ingestController.getOpaque().getThreadCount();
            if (count >= getActiveWorkers()) {
                return true;
            }
            LockSupport.parkNanos(1_000);
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
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        LatticeVertex controller = this.ingestController.getAndSet(null);
        controller.setDrain(true);

        this.resourceMonitor.close();
        PinnedThreadExecutor.closeAll();

        this.activeShardIds.set(null);
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

        try {
            controller.close();
        } catch (Exception e) {
            this.logger.error("Error closing ControlPlaneIngestController.", e);
        }

        INSTANCE.set(null);

        try {
            this.controlPlaneExecutor.shutdownNow();
            Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
        } catch (Exception ignored) {

        }
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
