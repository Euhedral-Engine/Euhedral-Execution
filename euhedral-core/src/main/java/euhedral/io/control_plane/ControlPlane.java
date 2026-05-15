package euhedral.io.control_plane;

import static euhedral.io.utils.MathFunctions.unsignedMultiplyHigh;

import euhedral.hardware_utils.EffectiveTopology;
import euhedral.hardware_utils.EffectiveTopology.EffectiveSocketTopology;
import euhedral.hardware_utils.EffectiveTopology.EffectiveSystemTopology;
import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hardware_utils.common.SystemUtilization.HardwareUtilization;
import euhedral.hardware_utils.common.SystemUtilization.SocketSnapshot;
import euhedral.io.flow_control.FluxEdge;
import euhedral.io.flow_control.FluxNode;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CloneableObject;
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
import org.reactivestreams.Publisher;
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

    protected final AtomicBoolean primed = new AtomicBoolean(false);
    protected final AtomicBoolean rebalancing = new AtomicBoolean(false);
    protected final AtomicReference<FluxNode> ingestController;

    protected final ControlPlaneShard baseShard;
    protected final ControlPlaneShard[] shards;
    protected final FluxEdge[] shardHandles;

    protected final AtomicReference<int[]> activeShardIds = new AtomicReference<>(new int[0]);
    protected final AtomicReference<int[]> weightedShardMap = new AtomicReference<>(new int[0]);
    protected final AtomicReference<int[]> reverseMapping = new AtomicReference<>(new int[0]);

    protected volatile int currentGlobalVersion = Integer.MIN_VALUE;
    protected volatile EffectiveSystemTopology effectiveTopology;


    protected ControlPlane(String name, CloneableObject cloneableObject,
            MeterRegistry meterRegistry) {
        this(name, null, cloneableObject, meterRegistry);
    }

    protected ControlPlane(String name, ControlPlaneShard baseShard,
            CloneableObject cloneableObject,
            MeterRegistry meterRegistry) {
        this.resourceMonitor = new FluxResourceMonitor(Duration.ofMillis(200));
        this.resourceMonitor.start();

        this.baseShard = Objects.requireNonNullElseGet(baseShard,
                () -> new ControlPlaneShard(-1, "BaseShard", cloneableObject,
                        resourceMonitor,
                        meterRegistry));

        this.name = name;
        this.logger = LoggerFactory.getLogger(name);
        this.effectiveTopology = EffectiveTopology.getEffectiveTopology();
        this.ingestController = new AtomicReference<>();
        this.shards = new ControlPlaneShard[SystemInfo.getMaxSocketId() + 1];
        this.shardHandles = new FluxEdge[SystemInfo.getMaxSocketId() + 1];

        this.controlPlaneExecutor = Executors.newFixedThreadPool(this.shards.length,
                r -> new Thread(r, name));

        this.shutdownHook = new Thread(this::close);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);

        init();
        try {
            EffectiveTopology.update(this.resourceMonitor.getUtilization());
        } catch (Exception e) {
            this.logger.error("Failed to update EffectiveTopology", e);
        }
        update(resourceMonitor.getUtilization());
        this.resourceMonitor.addListener().subscribe(this::update);
    }

    protected void init() {
        this.logger.info("Initializing");

        for (int i = 0; i < this.shards.length; i++) {
            this.shards[i] = createShard(i);
        }

        FluxNode controller = new FluxNode(this.name + "-GlobalDistributor",
                this.effectiveTopology.socketTopologies().size(), this::route, 0, false);
        this.ingestController.set(controller);
    }

    protected ControlPlaneShard createShard(int nodeId) {
        this.logger.info("Creating Shards");
        String shardName = this.name + "-ControlPlaneShard-" + nodeId;
        return this.baseShard.clone(nodeId, shardName);
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
        int nextVersion = EffectiveTopology.getGlobalVersion();

        if (this.currentGlobalVersion != nextVersion) {
            if (!this.primed.getOpaque()) {
                this.logger.info("Initializing the ControlPlane for topology V{}", nextVersion);
            } else {
                this.logger.warn(
                        "Detected change in global topology. Initiating global rebalance for topology V{}",
                        nextVersion);
            }
            handleSystemTopologyChange(utilization);
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
        this.effectiveTopology = EffectiveTopology.getEffectiveTopology();
        this.currentGlobalVersion = EffectiveTopology.getGlobalVersion();

        FluxNode controller = this.ingestController.get();

        BitSet newShards = this.effectiveTopology.effectiveSockets();
        for (int socket = newShards.nextSetBit(0); socket >= 0;
                socket = newShards.nextSetBit(socket + 1)) {
            if (this.shardHandles[socket] == null) {
                this.shardHandles[socket] = new FluxEdge(controller.getDrainFlag());
            }
        }
        remapIngestController();

        double quotaPool = utilization.quotaCpus();
        for (int socket = newShards.nextSetBit(0); socket >= 0;
                socket = newShards.nextSetBit(socket + 1)) {
            EffectiveSocketTopology topology = this.effectiveTopology.socketTopologies().get(socket);
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

        FluxNode controller = this.ingestController.get();
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
        int socketEffectiveCpus = this.effectiveTopology.socketTopologies().get(socketId).effectiveCpus()
                .cardinality();

        return ((double) socketEffectiveCpus / Math.max(1, totalEffectiveCpus)) * systemQuotaPool;
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        FluxNode controller = this.ingestController.getAndSet(null);
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
        FluxNode controller = this.ingestController.get();
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

    public void ingest(Publisher<? extends AbstractFrame> frameFlux) {
        if (this.closed.get()) {
            this.logger.error(
                    "Could not ingest from an upstream publisher. The ControlPlane is permanently closed.");
            return;
        }

        FluxNode controller = this.ingestController.get();
        controller.ingest(frameFlux);
    }
}
