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
import euhedral.io.frames.DummyInitFrame;
import euhedral.io.generics.CloneableObject;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import euhedral.io.metrics.CacheMetrics;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.QueuePartitionWrapper;
import io.euhedral_execution.data_structures.atomics.AtomicDouble;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicReference;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.BitSet;

import org.jctools.maps.NonBlockingHashMapLong;
import org.jspecify.annotations.NonNull;

public abstract class ControlPlaneCache extends LatticeVertex implements CloneableObject {

    protected static final VarHandle PRIMED;
    protected static final VarHandle TOTAL_BYTES;
    protected static final VarHandle TOTAL_COUNT;

    protected static final ThreadLocal<UpstreamQueue> UPSTREAM = new ThreadLocal<>();
    protected static final NonBlockingHashMapLong<ControlPlaneCache> CACHES =
            new NonBlockingHashMapLong<>();

    static {
        try {
            PRIMED = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneCache.class, "primed", boolean.class);
            TOTAL_BYTES = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneCache.class, "totalQueuedSizeBytes", long.class);
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

        double budget = config.L2MemoryBudget();
        budget = clampDouble(budget, 0, 1.0);
        if (budget == 0) {
            budget = 0.7;
        }

        int chunk = (int) Math.min(L2 / partitions, Integer.MAX_VALUE);
        chunk = (int) (chunk * budget);
        chunk = Integer.highestOneBit(chunk);
        chunk /= QueueUtils.REFERENCE_SIZE;
        return Math.max(chunk, 512);
    }

    protected final CacheConfig cacheConfig;
    protected final CacheMetrics metrics;

    protected final AtomicDouble capFactor = new AtomicDouble(1d);
    protected final PaddedAtomicLong memoryLimit;
    protected final PaddedLongAdder avgFrameSize;
    protected final long frameQuota;

    protected final PaddedAtomicReference<FlowRecorder> fillRecorder;
    protected final PaddedAtomicReference<FlowRecorder> fillBytesRecorder;
    protected final FlowRecorder drainRecorder;
    protected final FlowRecorder drainBytesRecorder;

    final QueuePartitionWrapper L2Cache;
    final int chunkSize;
    final int mask;

    boolean primed;
    long totalCount = 0L;
    long totalQueuedSizeBytes = 0L;

    private int head;

    public ControlPlaneCache(@NonNull CacheConfig cacheConfig) {
        super(getName(cacheConfig), cacheConfig.partitionsPerCpu(), RoutingFunction.DEFAULT,
                true);
        this.cacheConfig = cacheConfig;

        int partitions = cacheConfig.partitionsPerCpu();
        if (partitions <= 0 || cacheConfig.cloneConfig() == null) {
            this.metrics = null;
            this.memoryLimit = null;
            this.avgFrameSize = null;
            this.frameQuota = 0;
            this.L2Cache = null;
            this.chunkSize = 0;
            this.mask = 0;
            this.fillRecorder = null;
            this.fillBytesRecorder = null;
            this.drainRecorder = null;
            this.drainBytesRecorder = null;
        } else {
            CpuCacheLayout layout = SystemInfo.getCacheLayout(
                    cacheConfig.cloneConfig().getCpuSet()[0]);
            if (cacheConfig.registry() != null) {
                this.metrics = new CacheMetrics(cacheConfig.metricPrefix(), layout.maskL2(),
                        capFactor,
                        () -> (long) TOTAL_BYTES.getOpaque(this), cacheConfig.registry());
            } else {
                this.metrics = null;
            }
            this.memoryLimit = new PaddedAtomicLong(0);
            this.avgFrameSize = new PaddedLongAdder(partitions, true, true);
            this.chunkSize = getChunkSize(cacheConfig, partitions);
            this.frameQuota = (long) this.chunkSize * partitions;
            this.L2Cache = new QueuePartitionWrapper(
                    new PartitionedMpscQueue<>(partitions, this.chunkSize,
                            cacheConfig.maxPooledChunks()));
            this.mask = partitions - 1;
            this.fillRecorder = new PaddedAtomicReference<>(new FlowRecorder());
            this.fillBytesRecorder = new PaddedAtomicReference<>(new FlowRecorder());
            this.drainRecorder = new FlowRecorder();
            this.drainBytesRecorder = new FlowRecorder();

            BitSet mappings = new BitSet(partitions);
            mappings.set(0, partitions);
            LatticeEdge[] queueHandles = new LatticeEdge[partitions];

            for (int i = 0; i < partitions; i++) {
                queueHandles[i] = new LatticeEdge(super.drain);
                queueHandles[i].addDownstream(new PartitionSubscriber(i));
            }
            setDrain(true);
            super.setDownstreamMapping(mappings, queueHandles);
            setDrain(false);

            this.logger.debug("Partitions: {} PartitionChunkSize: {} CapacityPerQueueNode: {}",
                    partitions, this.chunkSize, partitions * this.chunkSize);
        }
    }

    public abstract double getPressure();

    public final long drain(DrainBuffer drainBuffer, int maxFill, long demand) {
        drainBuffer.reset();
        if (maxFill <= 0) {
            request(demand);
            return 0;
        }

        long totalDrain = 0;
        long totalBytesDrained = 0;
        long initialCount = (long) TOTAL_COUNT.getOpaque(this);
        if (initialCount > 0) {
            int cycles = 0;
            for (int i = 0; i < maxFill && cycles <= this.L2Cache.partitions(); ) {

                int drainCount = (int) this.L2Cache.drain(this.head, drainBuffer,
                        maxFill - totalDrain);
                long drainedBytes = drainBuffer.drainedBytes;

                cycles++;
                if (drainCount > 0) {
                    i += drainCount;
                    totalBytesDrained += drainedBytes;
                    totalDrain += drainCount;
                    if (drainCount > cacheConfig.ringWalkResetThreshold()) {
                        cycles = 0;
                    }
                }

                this.head = (this.head + 1) & mask;
                VarHandle.fullFence();
            }
        }
        if (totalDrain > 0) {
            TOTAL_COUNT.getAndAdd(this, -totalDrain);
            TOTAL_BYTES.getAndAdd(this, -totalBytesDrained);
        }
        drainBuffer.reset();
        if (totalDrain < maxFill) {
            pull(drainBuffer, maxFill - totalDrain);
        }
        long now = System.nanoTime();
        this.drainRecorder.record(now, totalDrain + drainBuffer.drainCount, false);
        this.drainBytesRecorder.record(now, totalBytesDrained + drainBuffer.drainedBytes, false);
        request(demand);

        totalDrain += drainBuffer.drainCount;
        drainBuffer.reset();
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

        double pressure = clampDouble(getPressure(), 0.0, 1.0);
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
            for (int j = 0; j < this.L2Cache.partitions(); j++) {
                for (int k = 0; k < chunkSize; k++) {
                    this.L2Cache.offer(j, DummyInitFrame.INSTANCE);
                }
            }
        }
        this.L2Cache.clear();
        this.fillRecorder.getOpaque().record(1, true);
        this.fillBytesRecorder.getOpaque().record(1, false);

        this.fillRecorder.getOpaque().reset(false);
        this.fillBytesRecorder.getOpaque().reset(false);

        this.capFactor.lazySet(1.0);
        this.avgFrameSize.lazyFill(0);
        this.memoryLimit.lazySet(0);
        TOTAL_COUNT.setOpaque(this, 0);
        TOTAL_BYTES.setOpaque(this, 0);
    }

    public final long getL2CacheCount() {
        return (long) TOTAL_COUNT.getOpaque(this);
    }

    public final long getL2MaxQueuedBytes() {
        double cap = this.capFactor.getAcquire();
        long byteQuota = this.frameQuota * (this.avgFrameSize.sum() / this.L2Cache.partitions());
        long hardwareMax = this.memoryLimit.getOpaque();
        if (hardwareMax > byteQuota) {
            return (long) (byteQuota * cap);
        }
        return (long) (hardwareMax * cap);
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

    protected class PartitionSubscriber implements LatticeReceiver {

        private final int idx;
        private final double smoothingFactor;

        public PartitionSubscriber(int idx) {
            this.idx = idx;
            double dt = 0.1;
            double tau = 2.0; // 2 Seconds
            double smoothingFactor = 1.0 - Math.exp(-dt / tau);

            if (!Double.isFinite(smoothingFactor) || smoothingFactor <= 0) {
                this.smoothingFactor = 0.0645; // Fallback to 1 - e^(-0.2/3.0)
            } else {
                this.smoothingFactor = clampDouble(smoothingFactor, 0.01, 1.0);
            }
        }

        @Override
        public void push(AbstractFrame frame) {
            while (!ControlPlaneCache.this.L2Cache.offer(this.idx, frame)) {
                Thread.onSpinWait();
            }

            long size = frame.getSizeBytes();
            long adjustedSize = size <= 0 ? 256 : size;
            ControlPlaneCache.this.avgFrameSize.getAndAccumulate(this.idx, adjustedSize,
                    this::ewma);

            TOTAL_BYTES.getAndAdd(ControlPlaneCache.this, adjustedSize);
            long count = (long) TOTAL_COUNT.getAndAdd(ControlPlaneCache.this, 1) + 1;
            if ((count & 63) == 0) {
                long now = System.nanoTime();
                ControlPlaneCache.this.fillRecorder.getPlain().record(now, 64, true);
                ControlPlaneCache.this.fillBytesRecorder.getPlain().record(now, adjustedSize, true);
            }
        }

        private long ewma(long curr, long next) {
            return (long) ((1 - this.smoothingFactor) * curr + this.smoothingFactor * next);
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
