package euhedral.io.control_plane;

import static euhedral.io.utils.MathFunctions.clampDouble;
import static euhedral.io.utils.MathFunctions.ewma;

import euhedral.atomics.AtomicDouble;
import euhedral.atomics.PaddedAtomicLong;
import euhedral.atomics.PaddedAtomicReference;
import euhedral.atomics.PaddedLongAdder;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuCacheLayout;
import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.hashing.HasherApi;
import euhedral.io.config.CacheConfig;
import euhedral.io.config.CloneConfig;
import euhedral.io.flow_control.LatticeEdge;
import euhedral.io.flow_control.LatticeVertex;
import euhedral.io.flow_control.UpstreamQueue;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.DummyInitFrame;
import euhedral.io.generics.CacheManager;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import euhedral.io.metrics.CacheMetrics;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import euhedral.io.utils.MathFunctions;
import euhedral.io.utils.QueuePartitionWrapper;
import euhedral.queues.PartitionedMpscQueue;
import euhedral.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.BitSet;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import org.jctools.maps.NonBlockingHashMapLong;
import org.jspecify.annotations.NonNull;

/// ## Partitioned deficit round-robin (DRR) cache layer
///
/// `ControlPlaneCache` is a partitioned queueing system that sits between ingestion and execution.
/// It implements a weighted DRR-like model to balance fairness, memory pressure, and throughput.
///
/// #### Each CPU-facing partition maintains independent quotas
///
/// ```java
/// int drainCount = queueRing.drain(partition, buffer, quota);
/// stats.quotaBytes -= drainBuffer.drainedBytes;
/// ```
///
/// The system continuously adjusts per-partition weights based on observed frame size and flow
/// variance, preventing hot partitions from starving others.
///
/// **This layer is the primary memory-aware rate limiter in Euhedral Core.**
public class ControlPlaneCache extends LatticeVertex implements CacheManager {

    protected static final VarHandle PARTITION_LOCK =
            MethodHandles.arrayElementVarHandle(boolean[].class);
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

    protected static int getPartitionCount(CacheConfig config) {
        CloneConfig cloneConfig = config.cloneConfig();
        if (cloneConfig != null) {
            CpuCacheLayout layout = SystemInfo.getCacheLayout(cloneConfig.getCpuSet()[0]);
            return config.partitionsPerCpu() * Integer.highestOneBit(layout.sharesL2());
        }
        return 0;
    }

    protected static int getChunkSize(CacheConfig config, int partitions) {
        CloneConfig cloneConfig = config.cloneConfig();
        if (cloneConfig == null) {
            return 512;
        }

        CpuCacheLayout layout = SystemInfo.getCacheLayout(cloneConfig.getCpuSet()[0]);
        long L2 = layout.bytesL2();

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

    protected final CacheConfig config;
    protected final CacheMetrics metrics;

    protected final AtomicDouble capFactor = new AtomicDouble(1d);
    protected final PaddedAtomicLong memoryLimit;
    protected final PaddedLongAdder avgFrameSize;
    protected final NonBlockingHashMapLong<DownstreamHandle> handles;
    protected final long frameQuota;

    protected final QueuePartitionWrapper queueRing;
    protected final int chunkSize;
    protected final int mask;
    protected final int[] heads;

    protected final PartitionStats[] partitionStats;
    protected final boolean[] partitionLocks;

    protected final PaddedAtomicReference<FlowRecorder> fillRecorder;
    protected final PaddedAtomicReference<FlowRecorder> fillBytesRecorder;

    protected boolean primed;
    protected long totalCount = 0L;
    protected long totalQueuedSizeBytes = 0L;

    public ControlPlaneCache(@NonNull CacheConfig config) {
        super(getName(config), getPartitionCount(config), RoutingFunction.DEFAULT,
                true);
        this.config = config;

        int partitions = getPartitionCount(config);
        if (partitions <= 0) {
            this.metrics = null;
            this.memoryLimit = null;
            this.avgFrameSize = null;
            this.handles = null;
            this.frameQuota = 0;
            this.queueRing = null;
            this.partitionStats = null;
            this.chunkSize = 0;
            this.mask = 0;
            this.heads = null;
            this.partitionLocks = new boolean[0];
            this.fillRecorder = null;
            this.fillBytesRecorder = null;
        } else {
            CpuCacheLayout layout = SystemInfo.getCacheLayout(config.cloneConfig().getCpuSet()[0]);
            if (config.registry() != null) {
                this.metrics = new CacheMetrics(config.metricPrefix(), layout.maskL2(), capFactor,
                        () -> (long) TOTAL_BYTES.getOpaque(this), config.registry());
            } else {
                this.metrics = null;
            }
            this.memoryLimit = new PaddedAtomicLong(0);
            this.avgFrameSize = new PaddedLongAdder(partitions, true, true);
            this.handles = new NonBlockingHashMapLong<>(4);
            this.chunkSize = getChunkSize(config, partitions);
            this.frameQuota = (long) this.chunkSize * partitions;
            this.queueRing = new QueuePartitionWrapper(
                    new PartitionedMpscQueue<>(partitions, this.chunkSize,
                            config.maxPooledChunks()));
            this.mask = partitions - 1;
            this.heads = new int[SystemInfo.getCpuCount()];
            this.partitionLocks = new boolean[partitions];
            this.fillRecorder = new PaddedAtomicReference<>(new FlowRecorder());
            this.fillBytesRecorder = new PaddedAtomicReference<>(new FlowRecorder());

            this.partitionStats = new PartitionStats[partitions];
            for (int i = 0; i < partitions; i++) {
                this.partitionStats[i] = new PartitionStats();
            }

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

            String[] chunks = layout.maskL2().split(",");
            StringJoiner sj = new StringJoiner(",");
            for (var c : chunks) {
                sj.add("0x" + c);
            }
            this.logger.debug("Initialized to serve cpus (little-endian): {}", sj);
            this.logger.debug("Partitions: {} PartitionChunkSize: {} CapacityPerQueueNode: {}",
                    partitions, this.chunkSize, partitions * this.chunkSize);
        }
    }

    /// Drains work from the cache-local DRR partitions into the execution buffer.
    ///
    /// Each partition maintains an adaptive byte quota that behaves similarly to deficit
    /// round-robin scheduling. Partitions that consistently drain work accumulate credit, while
    /// idle partitions naturally fall behind.
    ///
    /// Work is drained opportunistically:
    ///
    ///   - Try the current partition
    ///   - Walk partitions if contention or emptiness is encountered
    ///   - Refill quota when depleted
    ///   - Reset the ring walk when a productive partition is found
    ///
    /// The drain process is intentionally byte-oriented instead of item-oriented to better handle
    /// highly irregular frame sizes.
    ///
    /// Remaining capacity is filled directly from upstream if local queues underflow.
    public long drain(DownstreamHandle handle, DrainBuffer drainBuffer, int maxFill, long demand) {
        drainBuffer.reset();
        if (maxFill <= 0) {
            hookOnDrain(demand);
            return 0;
        }

        int cycles = 0;

        long totalDrain = 0;
        long totalBytesDrained = 0;

        long initialCount = (long) TOTAL_COUNT.getOpaque(this);
        for (int i = 0;
                i < maxFill && cycles <= this.queueRing.partitions() && initialCount > 0; ) {
            int lock = this.heads[handle.cpu];
            while (!acquireLock(lock)) {
                lock = (lock + 1) & mask;
                Thread.onSpinWait();
            }
            try {
                PartitionStats stats = this.partitionStats[lock];

                int quota = (int) stats.quotaBytes;
                if (quota <= 0) {
                    refillQueueQuota(stats);
                    quota = (int) stats.quotaBytes;
                }
                quota = (int) Math.min(quota, maxFill - totalDrain);

                int drainCount = (int) this.queueRing.drain(lock, drainBuffer, quota);
                long drainedBytes = drainBuffer.drainedBytes;

                cycles++;
                if (drainCount > 0) {
                    i += drainCount;
                    totalBytesDrained += drainedBytes;
                    totalDrain += drainCount;
                    stats.drainCycles++;
                    stats.quotaBytes -= drainedBytes;
                    stats.lastBytesDrained = drainedBytes;

                    recordDrainMetrics(lock, stats, drainCount);
                    if (drainCount > config.ringWalkResetThreshold()) {
                        cycles = 0;
                    }
                }

                this.heads[handle.cpu] = (lock + 1) & mask;
                VarHandle.fullFence();
            } finally {
                releaseLock(lock);
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
        handle.record(totalDrain, totalBytesDrained, drainBuffer);
        hookOnDrain(demand);

        totalDrain += drainBuffer.drainCount;
        drainBuffer.reset();
        return totalDrain;
    }

    public void hookOnDrain(long demand) {
        if (demand <= 0) {
            return;
        }

        if (getUpstreamCount() > 0) {
            UPSTREAM.get().request(demand);
        } else {
            super.request(demand);
        }
    }

    protected void refillQueueQuota(PartitionStats stats) {
        if (super.drain.getOpaque()) {
            stats.quotaBytes = chunkSize * stats.avgFrameSize.get();
            return;
        }
        stats.quotaBytes = Math.max(0, stats.quotaBytes);

        updateWeight(stats);
        stats.quotaBytes += stats.weight;
        stats.quotaBytes = stats.quotaBytes < 0 ? 1024 * 16 : stats.quotaBytes;
        stats.drainCycles = 0;
    }

    /// Dynamically adjusts the DRR weight for a partition based on observed frame sizes and
    /// workload variability.
    ///
    /// The target quantum tracks roughly `2x` the moving average frame size so larger frames
    /// naturally receive more drain budget.
    ///
    /// Variability is measured using the coefficient of variation (CV) from recent enqueue
    /// traffic:
    ///
    ///   - Stable traffic -> smaller deadband -> faster adaptation
    ///   - Chaotic traffic -> larger deadband -> smoother behavior
    ///
    /// Small changes are ignored to avoid oscillation and unnecessary weight churn.
    protected void updateWeight(PartitionStats stats) {
        long avgSize = stats.avgFrameSize.getAcquire();
        if (avgSize < 64) {
            avgSize = 64;
        }

        long targetQuantum = avgSize << 1;
        long currentWeight = stats.weight;

        FlowSnapshot flowSnapshot = this.fillBytesRecorder.getOpaque().getFlowSnapshot();
        this.fillBytesRecorder.getOpaque().refreshSnapshot(flowSnapshot, true);
        double cv = flowSnapshot.unitCV;
        double clampedCV = (cv > 0.5) ? 0.5 : MathFunctions.clampDouble(cv, 0.0, cv);

        long delta = targetQuantum - currentWeight;
        if (delta < 0) {
            delta = -delta;
        }

        int deadbandShift = (clampedCV > 0.3) ? 2 : 3;

        if (delta > (targetQuantum >> deadbandShift)) {
            stats.weight = smoothWeight(targetQuantum, clampedCV, currentWeight);
        }
    }

    protected long smoothWeight(long target, double cv, long weight) {
        double stepPercent = 0.25 - (cv * 0.5);
        if (stepPercent < 0.05) {
            stepPercent = 0.05;
        } else if (stepPercent > 0.25) {
            stepPercent = 0.25;
        }

        long maxStep = (long) (weight * stepPercent);
        long delta = target - weight;

        if (delta > maxStep) {
            target = weight + maxStep;
        } else if (delta < -maxStep) {
            target = weight - maxStep;
        }

        return (long) (weight * 0.9 + target * 0.1);
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        this.memoryLimit.getAndSet(snapshot.memoryLimit() * this.handles.size());

        double pressure = 0.0;
        try {
            for (DownstreamHandle handle : this.handles.values()) {
                pressure += handle.downstreamPressure.call();
            }
            pressure /= this.handles.size();
            pressure = clampDouble(pressure, 0.0, 1.0);
        } catch (Throwable ignored) {
            pressure = 1.0;
        }

        double target = 1.0 - (0.85 * pressure);

        double curr = this.capFactor.getPlain();
        double alpha = (target < curr) ? 0.2 : 0.02;
        double clamped = clampDouble(ewma(curr, target, alpha), 0.15, 1.0);
        this.capFactor.setRelease(clamped);
    }

    protected boolean acquireLock(int partition) {
        return (boolean) PARTITION_LOCK.compareAndSet(this.partitionLocks, partition, false, true);
    }

    protected void releaseLock(int partition) {
        PARTITION_LOCK.setRelease(this.partitionLocks, partition, false);
    }

    @Override
    public long getUpstreamCount() {
        UpstreamQueue upstream = UPSTREAM.get();
        if (upstream == null) {
            upstream = getThreadUpstreamQueue();
            UPSTREAM.set(upstream);
        }
        return upstream.getCachedUpCount();
    }

    public FlowRecorder getFillRecorder() {
        return this.fillRecorder.getPlain();
    }

    public FlowRecorder getFillBytesRecorder() {
        return this.fillBytesRecorder.getPlain();
    }

    @Override
    public void firstTouch() {
        if (!PRIMED.compareAndSet(this, false, true)) {
            return;
        }

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < this.queueRing.partitions(); j++) {
                for (int k = 0; k < chunkSize; k++) {
                    this.queueRing.offer(j, DummyInitFrame.INSTANCE);
                }
            }
        }
        this.queueRing.purge();
        Arrays.stream(partitionStats).forEach(PartitionStats::reset);
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

    public void addHandle(DownstreamHandle handle) {
        this.handles.put(handle.cpu, handle);
    }

    public void removeHandle(int cpu) {
        this.handles.remove(cpu);
    }

    public long getTotalCount() {
        return (long) TOTAL_COUNT.getOpaque(this);
    }

    public long getMaxQueuedBytes() {
        double cap = this.capFactor.getAcquire();
        long byteQuota = this.frameQuota * (this.avgFrameSize.sum() / this.queueRing.partitions());
        long hardwareMax = this.memoryLimit.getOpaque();
        if (hardwareMax > byteQuota) {
            return (long) (byteQuota * cap);
        }
        return (long) (hardwareMax * cap);
    }

    public long getProportionalMaxQueuedBytes() {
        int shares = this.handles.size();
        long max = getMaxQueuedBytes();
        return shares <= 2 ? max : max / shares;
    }

    public boolean isEmpty() {
        return (long) TOTAL_COUNT.getOpaque(this) <= 0;
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
    public ControlPlaneCache output() {
        return this;
    }

    @Override
    public boolean isDrained() {
        return isEmpty();
    }

    @Override
    public void setDrainMode(boolean value) {
        super.drain.set(value);
    }

    @Override
    public CacheManager clone(CloneConfig cloneConfig) {
        CpuCacheLayout layout = SystemInfo.getCacheLayout(cloneConfig.getCpuSet()[0]);

        long hash = HasherApi.getHash(layout.maskL2());
        return CACHES.computeIfAbsent(hash,
                (k) -> new ControlPlaneCache(this.config.clone(cloneConfig)));
    }

    @Override
    public void close() {
        super.close();
    }

    protected void recordDrainMetrics(int partition, PartitionStats stats, long drainCount) {
        if (this.metrics != null) {
            this.metrics.subQBacklogSummary.record(this.queueRing.getSizeBytes(partition));
            this.metrics.subQWeightSummary.record(stats.weight);
        }
    }

    public static class DownstreamHandle {

        public final int cpu;
        public final Callable<Double> downstreamPressure;
        public FlowRecorder drainRecorder;
        public FlowRecorder drainBytesRecorder;

        public DownstreamHandle(int cpu, Callable<Double> downstreamPressure) {
            this.cpu = cpu;
            this.drainRecorder = new FlowRecorder();
            this.drainBytesRecorder = new FlowRecorder();
            this.downstreamPressure = downstreamPressure;
        }

        public void record(long totalDrain, long totalBytesDrained, DrainBuffer drainBuffer) {
            long now = System.nanoTime();
            this.drainRecorder.record(now, totalDrain + drainBuffer.drainCount, true);
            this.drainBytesRecorder.record(now, totalBytesDrained + drainBuffer.drainedBytes, true);
        }
    }

    protected static class PartitionStats {

        public final PaddedAtomicLong avgFrameSize = new PaddedAtomicLong(1024);

        public long weight = 1024;
        public long drainCycles = 0;
        public long lastBytesDrained = 0;
        public long quotaBytes = 0;

        public void reset() {
            avgFrameSize.set(1024);
            weight = 1024;
            drainCycles = 0;
            lastBytesDrained = 0;
            quotaBytes = 0;
        }
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
            while (!ControlPlaneCache.this.queueRing.offer(this.idx, frame)) {
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
