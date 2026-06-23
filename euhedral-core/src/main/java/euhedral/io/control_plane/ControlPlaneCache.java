package euhedral.io.control_plane;

import static euhedral.io.utils.MathFunctions.clampDouble;
import static euhedral.io.utils.MathFunctions.ewma;

import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuCacheLayout;
import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.io.config.CacheConfig;
import euhedral.io.config.CloneConfig;
import euhedral.io.flow_control.LatticeEdge;
import euhedral.io.flow_control.LatticeVertex;
import euhedral.io.flow_control.UpstreamQueue;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.DummyFrame;
import euhedral.io.generics.CloneableObject;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import euhedral.io.metrics.CacheMetrics;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.QueueConsumer;
import io.euhedral_execution.data_structures.atomics.AtomicDouble;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicReference;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.BitSet;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.Getter;
import org.jctools.maps.NonBlockingHashMapLong;
import org.jspecify.annotations.NonNull;

public abstract class ControlPlaneCache extends LatticeVertex implements CloneableObject {

    protected static final VarHandle PRIMED;
    protected static final VarHandle TOTAL_COUNT;

    protected static final ThreadLocal<UpstreamQueue> UPSTREAM = new ThreadLocal<>();
    protected static final NonBlockingHashMapLong<ControlPlaneCache> CACHES =
            new NonBlockingHashMapLong<>();

    static {
        try {
            PRIMED = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneCache.class, "primed", boolean.class);
            TOTAL_COUNT = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneCache.class, "totalCount", long.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static String getName(CacheConfig config) {
        return config.cloneConfig() != null ? config.cloneConfig().shardName()
                                              + "-ControlPlaneCache-"
                                              + config.cloneConfig().coreId() : "ControlPlaneCache";
    }

    protected static int getChunkSize(CacheConfig config, int partitions) {
        CloneConfig cloneConfig = config.cloneConfig();
        if (cloneConfig == null) {
            return 512;
        }

        CpuCacheLayout layout = SystemInfo.getCacheLayout(cloneConfig.getCpuSet()[0]);
        long L2 = layout.bytesL2();
        if (layout.sharesL2() > 2) {
            L2 /= layout.sharesL2();
        }
        long L1 = layout.bytesL1();

        double budget = config.memoryBudget();
        budget = clampDouble(budget, 0, 1.0);
        if (budget <= 0 || Double.isNaN(budget) || Double.isInfinite(budget)) {
            throw new IllegalArgumentException("Memory budget must be greater than 0 and less than 1. Provided: " + budget);
        }

        long totalMemory = L2 + L1;
        totalMemory = totalMemory < 0 ? Long.MAX_VALUE : totalMemory;

        int chunk = (int) Math.min(totalMemory / partitions, Integer.MAX_VALUE);
        chunk = (int) (chunk * budget);
        chunk = Integer.highestOneBit(chunk);
        chunk /= QueueUtils.REFERENCE_SIZE;
        return Math.max(chunk, 512);
    }

    protected final CacheConfig cacheConfig;
    protected final CacheMetrics metrics;

    protected final AtomicDouble capFactor = new AtomicDouble(1d);
    protected final PaddedAtomicLong memoryLimit;
    @Getter
    protected final long frameQuota;

    protected final PaddedAtomicReference<FlowRecorder> fillRecorder;
    protected final FlowRecorder pullRecorder;
    protected final FlowRecorder drainRecorder;

    @Getter(AccessLevel.PROTECTED)
    private final PartitionedMpscQueue<AbstractFrame> cache;
    protected final int chunkSize;

    private final CacheTerminal cacheTerminal;

    boolean primed;
    long totalCount = 0L;

    public ControlPlaneCache(@NonNull CacheConfig cacheConfig) {
        super(getName(cacheConfig), cacheConfig.partitionsPerCpu(), (frame, mapSize) -> 0,
                null, RoutingPolicy.CACHE_LOCAL);
        this.cacheConfig = cacheConfig;

        int partitions = cacheConfig.partitionsPerCpu();
        if (partitions <= 0 || cacheConfig.cloneConfig() == null) {
            this.metrics = null;
            this.memoryLimit = null;
            this.frameQuota = 0;
            this.cache = null;
            this.cacheTerminal = null;
            this.chunkSize = 0;
            this.fillRecorder = null;
            this.pullRecorder = null;
            this.drainRecorder = null;
        } else {
            if (cacheConfig.registry() != null) {
                this.metrics = new CacheMetrics(cacheConfig.metricPrefix(), cacheConfig.cloneConfig().coreId() + "",
                        capFactor, cacheConfig.registry());
            } else {
                this.metrics = null;
            }
            this.memoryLimit = new PaddedAtomicLong(0);
            this.chunkSize = getChunkSize(cacheConfig, partitions);
            this.frameQuota = (long) this.chunkSize * partitions;
            this.cache =
                    new PartitionedMpscQueue<>(partitions, this.chunkSize,
                            cacheConfig.maxPooledChunks());
            this.cacheTerminal = new CacheTerminal(this);
            this.fillRecorder = new PaddedAtomicReference<>(new FlowRecorder());
            this.pullRecorder = new FlowRecorder();
            this.drainRecorder = new FlowRecorder();

            BitSet mappings = new BitSet(1);
            mappings.set(0);
            LatticeEdge[] queueHandles = new LatticeEdge[]{new LatticeEdge(super.drain)};
            queueHandles[0].addDownstream(this.cacheTerminal);

            setDrain(true);
            super.setDownstreamMapping(mappings, queueHandles);
            setDrain(false);

            this.logger.debug("Partitions: {} PartitionChunkSize: {} CapacityPerQueueNode: {}",
                    partitions, this.chunkSize, partitions * this.chunkSize);
        }
    }

    public final long pull(long limit) {
        if(limit <= 0) {
            return 0;
        }

        this.cacheTerminal.reset();
        long now = System.nanoTime();
        long added = super.pull(this.cacheTerminal, limit);
        this.pullRecorder.record(now, added, false);
        if(added > 0) {
            TOTAL_COUNT.getAndAdd(ControlPlaneCache.this, this.cacheTerminal.framesAdded);
        }
        return added;
    }

    public final long drain(QueueConsumer queueConsumer, long maxBufferFill) {
        if (maxBufferFill <= 0) {
            return 0;
        }
        queueConsumer.reset();

        long now = System.nanoTime();
        long initialCount = (long) TOTAL_COUNT.getOpaque(this);
        if (initialCount > 0) {
            long count = this.cache.drain(queueConsumer, maxBufferFill);
            while(queueConsumer.drainCount < maxBufferFill &&
                    count > this.cacheConfig.ringWalkResetThreshold()) {
                count = this.cache.drain(queueConsumer, maxBufferFill - queueConsumer.drainCount);
            }

            TOTAL_COUNT.getAndAdd(this, -queueConsumer.drainCount);
        }

        this.drainRecorder.record(now, queueConsumer.drainCount, true);

        long totalDrain = queueConsumer.drainCount;
        queueConsumer.reset();
        return totalDrain;
    }

    @Override
    public final void request(long demand) {
        if (demand <= 0) {
            return;
        }

        if (getUpstreamCount() > 0) {
            UPSTREAM.get().request(demand);
        } else {
            super.request(demand);
        }
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        this.memoryLimit.getAndSet(snapshot.memoryLimit());

        double pressure = 0;
        CloneConfig config = this.cacheConfig.cloneConfig();
        if(config != null) {
            for(int cpu : this.cacheConfig.cloneConfig().getCpuSet()) {
                pressure = Math.max(pressure, snapshot.cpuSnapshots()[cpu].pressure());
            }
            pressure = clampDouble(pressure, 0.0, 1.0);
            pressure = Math.round(pressure * 10_000.0) / 10_000.0;
        }

        double target = 1.0 - (0.85 * pressure);

        double curr = this.capFactor.getPlain();
        double alpha = (target < curr) ? 0.2 : 0.02;
        double clamped = clampDouble(ewma(curr, target, alpha), 0.15, 1.0);
        this.capFactor.setRelease(clamped);
    }

    @Override
    public final long getUpstreamCount() {
        UpstreamQueue upstream = UPSTREAM.get();
        if (upstream == null) {
            upstream = getThreadUpstreamQueue();
            UPSTREAM.set(upstream);
        }
        return upstream.getCachedUpCount();
    }

    @Override
    public void firstTouch() {
        if (!PRIMED.compareAndSet(this, false, true)) {
            return;
        }

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < this.cache.partitions(); j++) {
                for (int k = 0; k < chunkSize; k++) {
                    this.cache.offer(j, DummyFrame.INSTANCE);
                }
            }
        }
        this.cache.clear();
        this.fillRecorder.getOpaque().record(1, true);

        this.fillRecorder.getOpaque().reset(false);

        this.capFactor.lazySet(1.0);
        this.memoryLimit.lazySet(0);
        TOTAL_COUNT.setOpaque(this, 0);
    }

    public final long getCacheCount() {
        return (long) TOTAL_COUNT.getOpaque(this);
    }

    public final long getMaxQueueCount() {
        double cap = this.capFactor.getAcquire();
        return (long) (this.frameQuota * cap);
    }

    @Override
    public boolean setDownstreamMapping(BitSet active, LatticeEdge[] edges) {
        return false;
    }

    @Override
    public void input(LatticeSource stream) {
        if (stream instanceof LatticeEdge dh) {
            addUpstream(dh);
        } else {
            stream.addDownstream(this);
        }
    }

    @Override
    public int getCore() {
        return this.cacheConfig.getCore();
    }

    @Override
    public boolean isDrained() {
        return (long) TOTAL_COUNT.getOpaque(this) <= 0;
    }

    @Override
    public void setDrainMode(boolean value) {
        super.drain.set(value);
    }

    @Override
    public void close() {
        super.close();
    }

    private static class CacheTerminal implements LatticeReceiver, Consumer<AbstractFrame> {

        final ControlPlaneCache cpc;

        long framesAdded = 0;
        long bytesAdded = 0;

        CacheTerminal(ControlPlaneCache cpc) {
            this.cpc = cpc;
        }

        @Override
        public void push(AbstractFrame frame) {
            int idx = RoutingFunction.DEFAULT.route(frame, this.cpc.cache.partitions());
            while (!this.cpc.cache.offer(idx, frame)) {
                Thread.onSpinWait();
            }

            long count = (long) TOTAL_COUNT.getAndAdd(this.cpc, 1) + 1;
            if ((count & 63) == 0) {
                long now = System.nanoTime();
                this.cpc.fillRecorder.getPlain().record(now, 64, true);
            }
        }

        @Override
        public void accept(AbstractFrame frame) {
            this.framesAdded++;
            this.bytesAdded += frame.getSizeBytes();

            int idx = RoutingFunction.DEFAULT.route(frame, this.cpc.cache.partitions());
            while(!this.cpc.cache.offer(idx, frame)) {
                Thread.onSpinWait();
            }
        }

        void reset() {
            this.framesAdded = 0;
            this.bytesAdded = 0;
        }

        @Override
        public void addUpstream(LatticeSource subscription) {

        }

        @Override
        public void onError(Throwable throwable) {

        }

        @Override
        public void onComplete() {

        }
    }
}
