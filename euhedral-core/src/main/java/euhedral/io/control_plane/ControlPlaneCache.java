package euhedral.io.control_plane;

import static euhedral.io.utils.MathFunctions.clampDouble;
import static euhedral.io.utils.MathFunctions.ewma;

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
import io.euhedral_execution.data_structures.atomics.AtomicDouble;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicReference;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.BitSet;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jctools.maps.NonBlockingHashMapLong;
import org.jspecify.annotations.NonNull;

public class ControlPlaneCache extends LatticeVertex implements CacheManager {

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

    protected final boolean[] partitionLocks;

    protected final AtomicLong cacheLock = new AtomicLong(0);

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

    public long drain(DownstreamHandle handle, DrainBuffer drainBuffer, int maxFill, long demand) {
        drainBuffer.reset();
        if (maxFill <= 0) {
            hookOnDrain(demand);
            return 0;
        }

        long totalDrain = 0;
        long totalBytesDrained = 0;
        long initialCount = (long) TOTAL_COUNT.getOpaque(this);
        if(initialCount > 0) {
            long permission = this.cacheLock.getAndAdd(1);

            while(permission < 0) {
                while(permission < 0) {
                    permission = this.cacheLock.getOpaque();
                }
                permission = this.cacheLock.getAndAdd(1);
            }

            int cycles = 0;

            try {
                for (int i = 0; i < maxFill && cycles <= this.queueRing.partitions(); ) {

                    int drainCount = (int) this.queueRing.drain(handle.head, drainBuffer, maxFill - totalDrain);
                    long drainedBytes = drainBuffer.drainedBytes;

                    cycles++;
                    if (drainCount > 0) {
                        i += drainCount;
                        totalBytesDrained += drainedBytes;
                        totalDrain += drainCount;
                        if (drainCount > config.ringWalkResetThreshold()) {
                            cycles = 0;
                        }
                    }

                    this.heads[handle.cpu] = (handle.head + 1) & mask;
                    handle.head = (handle.head + 1) % handle.partitionCount + handle.assignments[0];
                    VarHandle.fullFence();
                }
            } finally {
                this.cacheLock.getAndAdd(-1);
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
        this.queueRing.clear();
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
        while (!this.cacheLock.compareAndSet(0, Long.MIN_VALUE)) {
            Thread.onSpinWait();
        }
        try {
            this.handles.put(handle.cpu, handle);

            int count = this.handles.size();

            DownstreamHandle lastHandle = null;
            int start = 0;
            int parts = this.queueRing.partitions() / count;
            for (var h : this.handles.values()) {
                lastHandle = h;
                h.assignments[0] = start;
                h.assignments[1] = start + parts;
                h.head = start;
                h.partitionCount = parts;
                start += parts;
            }
            if(lastHandle != null) {
                lastHandle.assignments[1] = this.queueRing.partitions();
                lastHandle.partitionCount = lastHandle.assignments[1] - lastHandle.assignments[0];
            }
            VarHandle.fullFence();
        } finally {
            this.cacheLock.setRelease(0);
        }
    }

    public void removeHandle(int cpu) {
        while (!this.cacheLock.compareAndSet(0, Long.MIN_VALUE)) {
            Thread.onSpinWait();
        }
        try {
            this.handles.remove(cpu);
            int count = this.handles.size();

            DownstreamHandle lastHandle = null;
            int start = 0;
            int parts = this.queueRing.partitions() / Math.max(count, 1);
            for (var h : this.handles.values()) {
                lastHandle = h;
                h.assignments[0] = start;
                h.assignments[1] = start + parts;
                h.partitionCount = parts;
                start += parts;
            }
            if(lastHandle != null) {
                lastHandle.assignments[1] = this.queueRing.partitions();
                lastHandle.partitionCount = lastHandle.assignments[1] - lastHandle.assignments[0];
            }
            VarHandle.fullFence();
        } finally {
            this.cacheLock.setRelease(0);
        }
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

    public static class DownstreamHandle {

        public final int cpu;
        public final Callable<Double> downstreamPressure;
        private final int[] assignments = new int[2];

        private int head;
        private int partitionCount;

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
