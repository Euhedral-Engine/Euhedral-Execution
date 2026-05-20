package euhedral.io.control_plane;

import static euhedral.io.utils.MathFunctions.unsignedMultiplyHigh;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hardware_utils.TopologyMapper;
import euhedral.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import euhedral.hardware_utils.TopologyMapper.EffectiveSystemTopology;
import euhedral.hardware_utils.common.SystemUtilization.HardwareUtilization;
import euhedral.hardware_utils.common.SystemUtilization.SocketSnapshot;
import euhedral.io.flow_control.ScaffoldingEdge;
import euhedral.io.flow_control.ScaffoldingNode;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.interfaces.IngestSink;
import euhedral.io.interfaces.ScaffoldingSource;
import euhedral.io.utils.FluxResourceMonitor;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public class ControlPlane implements AutoCloseable {

    private static final AtomicReference<ControlPlane> INSTANCE = new AtomicReference<>();

    public static ControlPlane get() {
        return INSTANCE.get();
    }

    public static ControlPlane getOrCreate(String name, ControlPlaneShard baseShard) {
        return INSTANCE.updateAndGet(curr -> {
            if (curr != null) {
                return curr;
            }

            return new ControlPlane(name, baseShard, null,
                    SystemInfo.getCpuSet(), null);
        });
    }

    public static ControlPlane getOrCreate(String name,
            CloneableObject cloneableObject, MeterRegistry meterRegistry) {
        return INSTANCE.updateAndGet(curr -> {
            if (curr != null) {
                return curr;
            }

            return new ControlPlane(name, cloneableObject,
                    SystemInfo.getCpuSet(),
                    meterRegistry);
        });
    }

    public static ControlPlane getOrCreate(String name, BitSet allowedCpus,
            CloneableObject cloneableObject, MeterRegistry meterRegistry) {
        return INSTANCE.updateAndGet(curr -> {
            if (curr != null) {
                return curr;
            }

            return new ControlPlane(name, cloneableObject, allowedCpus,
                    meterRegistry);
        });
    }

    protected final String name;
    protected final TopologyMapper topology;
    protected final FluxResourceMonitor resourceMonitor;
    protected final Logger logger;
    protected final ExecutorService controlPlaneExecutor;
    protected final AtomicBoolean closed = new AtomicBoolean(false);
    protected final Thread shutdownHook;

    protected final AtomicBoolean started = new AtomicBoolean(false);
    protected final AtomicBoolean primed = new AtomicBoolean(false);
    protected final AtomicBoolean rebalancing = new AtomicBoolean(false);
    protected final AtomicReference<ScaffoldingNode> ingestController;

    protected final ControlPlaneShard baseShard;
    protected final ControlPlaneShard[] shards;
    protected final ScaffoldingEdge[] shardHandles;

    protected final BitSet allowedCores;
    protected final AtomicReference<int[]> activeShardIds = new AtomicReference<>(new int[0]);
    protected final AtomicReference<int[]> weightedShardMap = new AtomicReference<>(new int[0]);
    protected final AtomicReference<int[]> reverseMapping = new AtomicReference<>(new int[0]);

    protected volatile int currentGlobalVersion = Integer.MIN_VALUE;
    protected volatile EffectiveSystemTopology effectiveTopology;


    protected ControlPlane(String name, CloneableObject cloneableObject, BitSet allowedCpus,
            MeterRegistry meterRegistry) {
        this(name, null, cloneableObject, allowedCpus, meterRegistry);
    }

    protected ControlPlane(String name, ControlPlaneShard baseShard,
            CloneableObject cloneableObject, BitSet allowedCpus,
            MeterRegistry meterRegistry) {
        this.topology = new TopologyMapper(allowedCpus);
        this.resourceMonitor = new FluxResourceMonitor(this.topology, Duration.ofMillis(200));

        this.baseShard = Objects.requireNonNullElseGet(baseShard,
                () -> new ControlPlaneShard(-1, "BaseShard", cloneableObject,
                        resourceMonitor,
                        meterRegistry));

        this.name = name;
        this.logger = LoggerFactory.getLogger(name);
        this.effectiveTopology = this.topology.getEffectiveTopology();
        this.ingestController = new AtomicReference<>();
        this.shards = new ControlPlaneShard[SystemInfo.getMaxSocketId() + 1];
        this.shardHandles = new ScaffoldingEdge[SystemInfo.getMaxSocketId() + 1];

        this.allowedCores = allowedCpus;
        this.controlPlaneExecutor = Executors.newFixedThreadPool(this.shards.length,
                r -> new Thread(r, name));

        this.shutdownHook = new Thread(this::close);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    public void start() {
        if (this.started.compareAndSet(false, true)) {
            this.resourceMonitor.start();

            init();
            this.topology.update(this.resourceMonitor.getUtilization());

            update(resourceMonitor.getUtilization());
            this.resourceMonitor.addListener().subscribe(this::update);
            while (this.rebalancing.get() || !this.ready()) {
                LockSupport.parkNanos(1_000L);
            }
        }
    }

    protected void init() {
        this.logger.info("Initializing");

        for (int i = 0; i < this.shards.length; i++) {
            this.shards[i] = createShard(i);
            this.logger.info("Created ControlPlaneShard on socket: {}", i);
        }

        ScaffoldingNode controller = new ScaffoldingNode(this.name + "-GlobalDistributor",
                this.effectiveTopology.socketTopologies().size(), this::route, false);
        this.ingestController.set(controller);
    }

    protected ControlPlaneShard createShard(int socketId) {
        String shardName = this.name + "-ControlPlaneShard-" + socketId;
        return this.baseShard.clone(socketId, shardName);
    }

    protected int route(AbstractFrame frame, int mapSize) {
        RoutingPolicy policy = frame.getRoutingPolicy();
        if (policy != null && policy.level > RoutingPolicy.ANY.level) {
            int[] reverseMapping = this.reverseMapping.getOpaque();
            CpuInfo location = frame.getOrigin();
            int socket = location != null ? location.socket() : -1;

            if (socket >= 0 && socket < reverseMapping.length
                    && (socket = reverseMapping[socket]) < mapSize
                    && socket >= 0) {
                return socket;
            }
        }

        int[] map = this.activeShardIds.getOpaque();
        int idx = (int) unsignedMultiplyHigh(frame.getCombinedHash(), map.length);
        return map[idx];
    }

    protected void update(HardwareUtilization utilization) {
        int nextVersion = this.topology.getGlobalVersion();

        if (this.currentGlobalVersion != nextVersion) {
            if (!this.primed.getOpaque()) {
                this.logger.info("Initializing the ControlPlane for global topology V{}",
                        nextVersion);
            } else {
                this.logger.warn(
                        "Detected change in global topology. Initiating global rebalance for topology V{}",
                        nextVersion);
            }
            handleSystemTopologyChange(utilization);
            if (this.effectiveTopology.effectiveCpus().cardinality() == 0) {
                logger.error("There are no usable cpus for this ControlPlane.");
            }
        } else {
            double quotaPool = utilization.quotaCpus();

            int[] sockets = this.activeShardIds.getOpaque();
            for (int socketId : sockets) {
                EffectiveSocketTopology topology = this.effectiveTopology.socketTopologies()
                        .get(socketId);
                ControlPlaneShard shard = this.shards[socketId];

                SocketSnapshot snapshot = utilization.getSocketSnapshot(socketId,
                        topology.effectiveCoreToCpu(),
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

    protected void handleSystemTopologyChange(HardwareUtilization utilization) {
        if (!this.rebalancing.compareAndSet(false, true)) {
            return;
        }
        this.effectiveTopology = this.topology.getEffectiveTopology();
        this.currentGlobalVersion = this.topology.getGlobalVersion();

        ScaffoldingNode controller = this.ingestController.get();

        BitSet newShards = this.effectiveTopology.effectiveSockets();
        for (int socket = newShards.nextSetBit(0); socket >= 0;
                socket = newShards.nextSetBit(socket + 1)) {
            if (this.shardHandles[socket] == null) {
                this.shardHandles[socket] = new ScaffoldingEdge(controller.getDrainFlag());
            }
        }
        remapIngestController();

        double quotaPool = utilization.quotaCpus();
        for (int socket = newShards.nextSetBit(0); socket >= 0;
                socket = newShards.nextSetBit(socket + 1)) {
            EffectiveSocketTopology topology = this.effectiveTopology.socketTopologies()
                    .get(socket);
            SocketSnapshot snapshot = utilization.getSocketSnapshot(socket,
                    topology.effectiveCoreToCpu(),
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

    protected void remapIngestController() {
        this.ingestController.get().setDrain(true);
        BitSet effectiveSockets = this.effectiveTopology.effectiveSockets();
        BitSet effectiveCpus = this.effectiveTopology.effectiveCpus();

        int idx = 0;
        int[] weightedShardMap = new int[this.effectiveTopology.effectiveCpus().cardinality()];
        for (int i = effectiveCpus.nextSetBit(0); i >= 0; i = effectiveCpus.nextSetBit(i + 1)) {
            weightedShardMap[idx++] = SystemInfo.getCpuInfo(i).socket();
        }

        ScaffoldingNode controller = this.ingestController.get();
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

    protected double getShardQuota(int socketId, double systemQuotaPool) {
        int totalEffectiveCpus = this.effectiveTopology.effectiveCpus().cardinality();
        int socketEffectiveCpus = this.effectiveTopology.socketTopologies().get(socketId)
                .effectiveCpus()
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
            count += shard.getActiveCores();
        }
        return count;
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        ScaffoldingNode controller = this.ingestController.getAndSet(null);
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

    public boolean isDrained() {
        ScaffoldingNode controller = this.ingestController.get();
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

    public void ingest(IngestSink sink) {
        ingest(sink.getDelegate());
    }

    public void ingest(ScaffoldingSource stream) {
        if (this.closed.getOpaque()) {
            throw new RuntimeException("Could not ingest from an upstream publisher. The ControlPlane is permanently closed.");
        }
        if(!this.started.getOpaque()) {
            throw new RuntimeException("Could not ingest from an upstream publisher. The ControlPlane is not started.");
        }

        ScaffoldingNode controller = this.ingestController.get();
        controller.ingest(stream);
    }
}
