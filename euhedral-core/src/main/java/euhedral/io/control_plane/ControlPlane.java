package euhedral.io.control_plane;

import euhedral.io.flow_control.FluxEdge;
import euhedral.io.flow_control.FluxNode;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.utils.NumaMapper;
import euhedral.io.utils.NumaMapper.NodeTopology;
import euhedral.io.utils.NumaMapper.SystemTopology;
import euhedral.io.utils.ResourceMonitor;
import euhedral.io.utils.SystemUtilization.HardwareUtilization;
import euhedral.io.utils.SystemUtilization.NodeSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.BitSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.openhft.affinity.AffinityLock;
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
    protected final NumaMapper numaMapper;
    protected final ResourceMonitor resourceMonitor;
    protected final Logger logger;
    protected final ExecutorService controlPlaneExecutor;
    protected final AtomicBoolean closed = new AtomicBoolean(false);
    protected final Thread shutdownHook;

    protected final SystemTopology systemTopology;
    protected final AtomicBoolean rebalancing = new AtomicBoolean(false);
    protected final AtomicReference<FluxNode> ingestController;

    protected final ControlPlaneShard baseShard;
    protected final ControlPlaneShard[] shards;
    protected final FluxEdge[] shardHandles;

    protected volatile int[] activeNodeIds = new int[0];
    protected volatile int currentGlobalVersion = Integer.MIN_VALUE;

    protected volatile boolean primed = false;
    protected volatile int[] weightedShardMap = new int[0];

    protected ControlPlane(String name, CloneableObject cloneableObject,
            MeterRegistry meterRegistry) {
        this(name, null, cloneableObject, meterRegistry);
    }

    protected ControlPlane(String name, ControlPlaneShard baseShard,
            CloneableObject cloneableObject,
            MeterRegistry meterRegistry) {
        this.resourceMonitor = new ResourceMonitor(Duration.ofMillis(200));
        resourceMonitor.start();

        if (baseShard == null) {
            this.baseShard = new ControlPlaneShard(-1, "BaseShard", cloneableObject,
                    resourceMonitor,
                    meterRegistry);
        } else {
            this.baseShard = baseShard;
        }

        this.name = name;
        this.numaMapper = NumaMapper.INSTANCE;
        this.logger = LoggerFactory.getLogger(name);
        this.systemTopology = numaMapper.getSystemTopology();
        this.ingestController = new AtomicReference<>();
        this.shards = new ControlPlaneShard[numaMapper.getNodeCount()];
        this.shardHandles = new FluxEdge[numaMapper.getNodeCount()];

        this.controlPlaneExecutor = Executors.newFixedThreadPool(shards.length);

        this.shutdownHook = new Thread(this::close);
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        init();
        NumaMapper.INSTANCE.update(resourceMonitor.getUtilization());
        update(resourceMonitor.getUtilization());
        resourceMonitor.addListener().subscribe(this::update);
    }

    protected void init() {
        logger.info("Initializing");

        for (int i = 0; i < this.shards.length; i++) {
            this.shards[i] = createShard(i);
        }

        FluxNode controller = new FluxNode(name + "-GlobalDistributor",
                systemTopology.nodeTopologies().length(), this::route, false);
        this.ingestController.set(controller);
    }

    protected ControlPlaneShard createShard(int nodeId) {
        logger.info("Creating Shards");
        String shardName = this.name + "-ControlPlaneShard-" + nodeId;
        return baseShard.clone(nodeId, shardName);
    }

    protected int route(AbstractFrame frame, int mapSize) {
        int idx = (int) Math.unsignedMultiplyHigh(frame.getCombinedHash(), weightedShardMap.length);
        return weightedShardMap[idx];
    }

    protected void update(HardwareUtilization utilization) {
        int nextVersion = this.systemTopology.globalVersion().get();

        if (this.currentGlobalVersion != nextVersion) {
            if (!primed) {
                logger.info("Initializing the ControlPlane for version {}", nextVersion);
            } else {
                logger.warn(
                        "Detected change in global topology. Initiating global rebalance for version {}",
                        nextVersion);
            }
            this.currentGlobalVersion = nextVersion;
            handleSystemTopologyChange(utilization);
        } else {
            double quotaPool = utilization.quotaCpus();

            int[] nodes = this.activeNodeIds;
            for (int nodeId : nodes) {
                NodeTopology topology = systemTopology.nodeTopologies().get(nodeId);
                ControlPlaneShard shard = this.shards[nodeId];

                NodeSnapshot snapshot = utilization.getNodeSnapshot(nodeId,
                        topology.effectiveCoreToCpu().get(),
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

        FluxNode controller = this.ingestController.get();
        BitSet newNodes = systemTopology.effectiveNodes().get();
        for (int node = newNodes.nextSetBit(0); node >= 0; node = newNodes.nextSetBit(node + 1)) {
            if (shardHandles[node] == null) {
                shardHandles[node] = new FluxEdge(controller.getDrainFlag());
            }
        }
        remapIngestController();

        double quotaPool = utilization.quotaCpus();
        for (int node = newNodes.nextSetBit(0); node >= 0; node = newNodes.nextSetBit(node + 1)) {
            NodeTopology topology = systemTopology.nodeTopologies().get(node);
            NodeSnapshot snapshot = utilization.getNodeSnapshot(node,
                    topology.effectiveCoreToCpu().get(),
                    getShardQuota(node, quotaPool));

            if (!shards[node].isStarted()) {
                startShard(node, snapshot, topology);
            } else {
                shards[node].update(snapshot, topology);
            }
        }

        int idx = 0;
        int[] nextNodes = new int[newNodes.cardinality()];
        for (int i = newNodes.nextSetBit(0); i >= 0; i = newNodes.nextSetBit(i + 1)) {
            nextNodes[idx++] = i;
        }

        activeNodeIds = nextNodes;

        ingestController.get().setDrain(false);
        if (!this.primed) {
            this.primed = true;
            this.rebalancing.set(false);
            return;
        }

        CompletableFuture.runAsync(() -> {
            final AtomicInteger shutDown = new AtomicInteger(0);
            for (int i = 0; i < shards.length; i++) {
                if (!newNodes.get(i)) {
                    shutDown.incrementAndGet();
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
        BitSet effectiveNodes = systemTopology.effectiveNodes().get();
        BitSet effectiveCpus = systemTopology.effectiveCpus().get();

        int idx = 0;
        int[] weightedShardMap = new int[systemTopology.effectiveCpus().get().cardinality()];
        for (int i = effectiveCpus.nextSetBit(0); i >= 0; i = effectiveCpus.nextSetBit(i + 1)) {
            weightedShardMap[idx++] = numaMapper.getNodeId(numaMapper.getPhysicalCore(i));
        }

        FluxNode controller = ingestController.get();
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!controller.setDownstreamMapping(effectiveNodes, shardHandles)) {
            LockSupport.parkNanos(10_000);
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        this.weightedShardMap = weightedShardMap;
    }

    protected void startShard(int shardId, NodeSnapshot snapshot, NodeTopology topology) {
        if (shards[shardId].isStarted()) {
            return;
        }

        shards[shardId].start(snapshot, topology, shardHandles[shardId]);
    }

    protected double getShardQuota(int nodeId, double systemQuotaPool) {
        int totalEffectiveCpus = systemTopology.effectiveCpus().get().cardinality();
        int nodeEffectiveCpus = systemTopology.nodeTopologies().get(nodeId).effectiveCpus().get()
                .cardinality();

        return ((double) nodeEffectiveCpus / Math.max(1, totalEffectiveCpus)) * systemQuotaPool;
    }

    @Override
    public void close() {
        if(!closed.compareAndSet(false, true)) {
            return;
        }

        resourceMonitor.close();

        FluxNode controller = ingestController.getAndSet(null);
        controller.setDrain(true);
        this.activeNodeIds = null;
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

        AffinityLock.dumpLocks();

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

    public void ingest(Publisher<AbstractFrame> frameFlux) {
        if(closed.get()) {
            logger.error(
                    "Could not ingest from an upstream publisher. The ControlPlane is permanently closed.");
            return;
        }

        FluxNode controller = ingestController.get();
        controller.ingest(frameFlux);
    }
}
