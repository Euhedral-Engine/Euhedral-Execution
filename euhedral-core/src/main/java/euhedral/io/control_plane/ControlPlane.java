package euhedral.io.control_plane;

import static euhedral.io.utils.MathFunctions.unsignedMultiplyHigh;

import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.io.flow_control.FluxEdge;
import euhedral.io.flow_control.FluxNode;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.hardware_utils.EffectiveTopology;
import euhedral.hardware_utils.EffectiveTopology.EffectiveSocketTopology;
import euhedral.hardware_utils.EffectiveTopology.EffectiveSystemTopology;
import euhedral.io.utils.FluxResourceMonitor;
import euhedral.hardware_utils.common.SystemUtilization.HardwareUtilization;
import euhedral.hardware_utils.common.SystemUtilization.SocketSnapshot;
import euhedral.io.hardware_utils.pinning.PinnedThreadExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

            return new ControlPlane(name, baseShard, null, null);
        });
    }

    public static ControlPlane getOrCreate(String name,
            CloneableObject cloneableObject, MeterRegistry meterRegistry) {
        return INSTANCE.updateAndGet(curr -> {
            if (curr != null) {
                return curr;
            }

            return new ControlPlane(name, cloneableObject,
                    meterRegistry);
        });
    }

    protected final String name;
    protected final FluxResourceMonitor resourceMonitor;
    protected final Logger logger;
    protected final ExecutorService controlPlaneExecutor;
    protected final AtomicBoolean closed = new AtomicBoolean(false);
    protected final Thread shutdownHook;

    protected final AtomicBoolean rebalancing = new AtomicBoolean(false);
    protected final AtomicReference<FluxNode> ingestController;

    protected final ControlPlaneShard baseShard;
    protected final ControlPlaneShard[] shards;
    protected final FluxEdge[] shardHandles;

    protected volatile int[] activeShardIds = new int[0];
    protected volatile int currentGlobalVersion = Integer.MIN_VALUE;
    protected volatile EffectiveSystemTopology effectiveTopology;

    protected volatile boolean primed = false;
    protected volatile int[] weightedShardMap = new int[0];
    protected volatile int[] reverseMapping = new int[0];

    protected ControlPlane(String name, CloneableObject cloneableObject,
            MeterRegistry meterRegistry) {
        this(name, null, cloneableObject, meterRegistry);
    }

    protected ControlPlane(String name, ControlPlaneShard baseShard,
            CloneableObject cloneableObject,
            MeterRegistry meterRegistry) {
        this.resourceMonitor = new FluxResourceMonitor(Duration.ofMillis(200));
        resourceMonitor.start();

        if (baseShard == null) {
            this.baseShard = new ControlPlaneShard(-1, "BaseShard", cloneableObject,
                    resourceMonitor,
                    meterRegistry);
        } else {
            this.baseShard = baseShard;
        }

        this.name = name;
        this.logger = LoggerFactory.getLogger(name);
        this.effectiveTopology = EffectiveTopology.getEffectiveTopology();
        this.ingestController = new AtomicReference<>();
        this.shards = new ControlPlaneShard[SystemInfo.getMaxSocketId() + 1];
        this.shardHandles = new FluxEdge[SystemInfo.getMaxSocketId() + 1];

        this.controlPlaneExecutor = Executors.newFixedThreadPool(shards.length, r -> new Thread(r, name));

        this.shutdownHook = new Thread(this::close);
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        init();
        try {
            EffectiveTopology.update(resourceMonitor.getUtilization());
        } catch (Exception e) {
            logger.error("Failed to update EffectiveTopology", e);
        }
        update(resourceMonitor.getUtilization());
        resourceMonitor.addListener().subscribe(this::update);
    }

    protected void init() {
        logger.info("Initializing");

        for (int i = 0; i < this.shards.length; i++) {
            this.shards[i] = createShard(i);
        }

        FluxNode controller = new FluxNode(name + "-GlobalDistributor",
                effectiveTopology.socketTopologies().size(), this::route, 0, false);
        this.ingestController.set(controller);
    }

    protected ControlPlaneShard createShard(int nodeId) {
        logger.info("Creating Shards");
        String shardName = this.name + "-ControlPlaneShard-" + nodeId;
        return baseShard.clone(nodeId, shardName);
    }

    protected int route(AbstractFrame frame, int mapSize) {
        RoutingPolicy policy = frame.getRoutingPolicy();
        if (policy != null && policy.level > RoutingPolicy.ANY.level) {
            int[] reverseMapping = this.reverseMapping;
            CpuInfo location = frame.getOrigin();
            int node = location != null ? location.socket() : -1;

            if (node >= 0 && node < reverseMapping.length && (node = reverseMapping[node]) < mapSize
                    && node >= 0) {
                return node;
            }
        }

        int idx = (int) unsignedMultiplyHigh(frame.getCombinedHash(), weightedShardMap.length);
        return weightedShardMap[idx];
    }

    protected void update(HardwareUtilization utilization) {
        int nextVersion = EffectiveTopology.getGlobalVersion();

        if (this.currentGlobalVersion != nextVersion) {
            if (!primed) {
                logger.info("Initializing the ControlPlane for topology V{}", nextVersion);
            } else {
                logger.warn(
                        "Detected change in global topology. Initiating global rebalance for topology V{}",
                        nextVersion);
            }
            handleSystemTopologyChange(utilization);
        } else {
            double quotaPool = utilization.quotaCpus();

            int[] nodes = this.activeShardIds;
            for (int nodeId : nodes) {
                EffectiveSocketTopology topology = effectiveTopology.socketTopologies().get(nodeId);
                ControlPlaneShard shard = this.shards[nodeId];

                SocketSnapshot snapshot = utilization.getSocketSnapshot(nodeId,
                        topology.effectiveCoreToCpu(),
                        getShardQuota(nodeId, quotaPool));
                CompletableFuture.runAsync(() -> {
                    if (!shard.isStarted()) {
                        logger.info("Starting shard");
                        startShard(nodeId, snapshot, topology);
                    } else {
                        shard.update(snapshot, topology);
                    }
                }, controlPlaneExecutor);
            }
        }
    }

    protected void handleSystemTopologyChange(HardwareUtilization utilization) {
        if (!rebalancing.compareAndSet(false, true)) {
            return;
        }
        this.effectiveTopology = EffectiveTopology.getEffectiveTopology();
        this.currentGlobalVersion = EffectiveTopology.getGlobalVersion();

        FluxNode controller = this.ingestController.get();

        BitSet newShards = effectiveTopology.effectiveSockets();
        for (int node = newShards.nextSetBit(0); node >= 0; node = newShards.nextSetBit(node + 1)) {
            if (shardHandles[node] == null) {
                shardHandles[node] = new FluxEdge(controller.getDrainFlag());
            }
        }
        remapIngestController();

        double quotaPool = utilization.quotaCpus();
        for (int node = newShards.nextSetBit(0); node >= 0; node = newShards.nextSetBit(node + 1)) {
            EffectiveSocketTopology topology = effectiveTopology.socketTopologies().get(node);
            SocketSnapshot snapshot = utilization.getSocketSnapshot(node,
                    topology.effectiveCoreToCpu(),
                    getShardQuota(node, quotaPool));

            if (!shards[node].isStarted()) {
                startShard(node, snapshot, topology);
            } else {
                shards[node].update(snapshot, topology);
            }
        }

        int idx = 0;
        int[] nextNodes = new int[newShards.cardinality()];
        int[] reverseMapping = new int[SystemInfo.getMaxSocketId() + 1];
        Arrays.fill(reverseMapping, -1);

        for (int i = newShards.nextSetBit(0); i >= 0; i = newShards.nextSetBit(i + 1)) {
            reverseMapping[i] = idx;
            nextNodes[idx++] = i;
        }

        AtomicInteger shutDown = new AtomicInteger(0);
        for(int i : activeShardIds) {
            if(!newShards.get(i)) {
                shutDown.incrementAndGet();
            }
        }

        this.activeShardIds = nextNodes;
        this.reverseMapping = reverseMapping;

        ingestController.get().setDrain(false);
        if (!this.primed) {
            this.primed = true;
            this.rebalancing.set(false);
            return;
        }

        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < shards.length; i++) {
                if (!newShards.get(i)) {
                    shutDown.decrementAndGet();
                    shards[i].shutDownShard(shutDown);
                }
            }
            while (shutDown.get() != 0) {
                LockSupport.parkNanos(1_000);
            }
            this.rebalancing.set(false);
        }, controlPlaneExecutor);
    }

    protected void remapIngestController() {
        ingestController.get().setDrain(true);
        BitSet effectiveNodes = effectiveTopology.effectiveSockets();
        BitSet effectiveCpus = effectiveTopology.effectiveCpus();

        int idx = 0;
        int[] weightedShardMap = new int[effectiveTopology.effectiveCpus().cardinality()];
        for (int i = effectiveCpus.nextSetBit(0); i >= 0; i = effectiveCpus.nextSetBit(i + 1)) {
            weightedShardMap[idx++] = SystemInfo.getCpuInfo(i).socket();
        }

        FluxNode controller = ingestController.get();
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!controller.setDownstreamMapping(effectiveNodes, shardHandles)) {
            LockSupport.parkNanos(5_000);
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        this.weightedShardMap = weightedShardMap;
    }

    protected void startShard(int shardId, SocketSnapshot snapshot, EffectiveSocketTopology topology) {
        if (shards[shardId].isStarted()) {
            return;
        }

        shards[shardId].start(snapshot, topology, shardHandles[shardId]);
    }

    protected double getShardQuota(int nodeId, double systemQuotaPool) {
        int totalEffectiveCpus = effectiveTopology.effectiveCpus().cardinality();
        int nodeEffectiveCpus = effectiveTopology.socketTopologies().get(nodeId).effectiveCpus().cardinality();

        return ((double) nodeEffectiveCpus / Math.max(1, totalEffectiveCpus)) * systemQuotaPool;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        FluxNode controller = ingestController.getAndSet(null);
        controller.setDrain(true);

        resourceMonitor.close();
        PinnedThreadExecutor.closeAll();

        this.activeShardIds = null;
        for (int i = 0; i < shards.length; i++) {
            if (shards[i] != null) {
                try {
                    shardHandles[i] = null;
                    shards[i].close();
                } catch (Exception e) {
                    logger.error("Error closing shard {}", shards[i].getShardName(), e);
                } finally {
                    shards[i] = null;
                }
            }
        }

        try {
            controller.close();
        } catch (Exception e) {
            logger.error("Error closing ControlPlaneIngestController.", e);
        }

        INSTANCE.set(null);

        try {
            controlPlaneExecutor.shutdownNow();
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (Exception ignored) {

        }
    }

    public boolean isDrained() {
        FluxNode controller = ingestController.get();
        if (controller != null && !controller.isDrained()) {
            return false;
        }

        for (ControlPlaneShard shard : shards) {
            if (shard != null && !shard.isDrained()) {
                return false;
            }
        }
        return true;
    }

    public void ingest(Publisher<? extends AbstractFrame> frameFlux) {
        if (closed.get()) {
            logger.error(
                    "Could not ingest from an upstream publisher. The ControlPlane is permanently closed.");
            return;
        }

        FluxNode controller = ingestController.get();
        controller.ingest(frameFlux);
    }
}
