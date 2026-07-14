package io.euhedral_execution.core.control_plane;

import static io.euhedral_execution.core.utils.MathFunctions.clampDouble;
import static io.euhedral_execution.core.utils.MathFunctions.ewma;

import io.euhedral_execution.core.config.CacheConfig;
import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.DummyFrame;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.metrics.CacheMetrics;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.text.NumberFormat;
import java.util.BitSet;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ControlPlaneCache extends LatticeVertex implements CloneableObject {

    protected static final VarHandle CAP_FACTOR;
    protected static final VarHandle PRIMED;
    protected static final VarHandle TOTAL_COUNT;

    static {
        try {
            CAP_FACTOR = MethodHandles.lookup().findVarHandle(ControlPlaneCache.class, "capFactor", double.class);
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
        if (budget <= 0 || !Double.isFinite(budget)) {
            throw new IllegalArgumentException(
                    "Memory budget must be greater than 0 and less than 1. Provided: " + config.memoryBudget());
        }

        long totalMemory = L2 + L1;
        totalMemory = totalMemory < 0 ? Long.MAX_VALUE : totalMemory;

        int chunk = (int) Math.min(totalMemory / partitions, Integer.MAX_VALUE);
        chunk = (int) (chunk * budget);
        chunk = Integer.highestOneBit(chunk);
        chunk /= QueueUtils.REFERENCE_SIZE;
        return Math.max(chunk, 512);
    }

    private final Logger logger;
    private final CacheConfig cacheConfig;
    private final CacheMetrics metrics;
    private final int core;

    @Getter(AccessLevel.PROTECTED)
    private final PartitionedMpscQueue<AbstractFrame> localCache;
    private final int chunkSize;

    private final CacheTerminal cacheTerminal;

    @Getter
    private final long frameQuota;

    boolean primed;
    double capFactor = 1.0;
    long totalCount = 0L;

    public ControlPlaneCache(@NonNull CacheConfig cacheConfig) {
        super(getName(cacheConfig), 1, (frame, mapSize) -> 0, 0, RoutingPolicy.CACHE_LOCAL);
        this.cacheConfig = cacheConfig;

        int partitions = cacheConfig.partitions();
        if(partitions <= 0) {
            throw new IllegalArgumentException("Partitions must be greater than 0. Provided: " + cacheConfig.partitions());
        }
        if (cacheConfig.cloneConfig() == null) {
            this.logger = null;
            this.metrics = null;
            this.localCache = null;
            this.chunkSize = 0;
            this.cacheTerminal = null;
            this.frameQuota = 0;
            this.core = -1;
        } else {
            this.logger = LoggerFactory.getLogger(getName(cacheConfig));
            this.metrics = new CacheMetrics(cacheConfig, () -> (long) TOTAL_COUNT.getAcquire(this));
            this.chunkSize = getChunkSize(cacheConfig, partitions);
            this.frameQuota = (long) this.chunkSize * partitions;
            this.core = cacheConfig.getCore();
            this.localCache = new PartitionedMpscQueue<>(partitions, this.chunkSize,
                    cacheConfig.maxPooledChunks());
            this.cacheTerminal = new CacheTerminal(this);

            BitSet mappings = new BitSet(1);
            mappings.set(0);
            LatticeEdge[] terminal = new LatticeEdge[] {new LatticeEdge(super.drain)};
            terminal[0].addDownstream(this.cacheTerminal);

            setDrain(true);
            super.setDownstreamMapping(mappings, terminal);
            setDrain(false);

            this.logger.debug("Partitions: {} PartitionChunkSize: {} CacheCapacity: {}", partitions,
                    NumberFormat.getNumberInstance().format(this.chunkSize),
                    NumberFormat.getNumberInstance().format((long) partitions * this.chunkSize));
        }
    }

    public final long pull(long limit) {
        if (limit <= 0) {
            return 0;
        }

        this.cacheTerminal.reset();
        long added = super.pull(this.cacheTerminal, limit);
        if (added > 0) {
            TOTAL_COUNT.getAndAdd(ControlPlaneCache.this, this.cacheTerminal.framesAdded);
        }
        return added;
    }

    public final long drain(Consumer<AbstractFrame> consumer, long limit) {
        if (limit <= 0) {
            return 0;
        }

        long total = 0;
        long initialCount = (long) TOTAL_COUNT.getOpaque(this);
        if (initialCount > 0) {
            total = this.localCache.drain(consumer, limit);
            long count = total;
            while (total < limit
                    && count > this.cacheConfig.ringWalkResetThreshold()) {
                count = this.localCache.drain(consumer, limit - total);
                total += count;
            }

            TOTAL_COUNT.getAndAdd(this, -total);
        }

        return total;
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        double pressure = 0;
        CloneConfig config = this.cacheConfig.cloneConfig();
        if (config != null) {
            for (int cpu : this.cacheConfig.cloneConfig().getCpuSet()) {
                pressure = Math.max(pressure, snapshot.cpuSnapshots()[cpu].pressure());
            }
            pressure = clampDouble(pressure, 0.0, 1.0);
            pressure = Math.round(pressure * 10_000.0) / 10_000.0;
        }

        double target = 1.0 - (0.85 * pressure);

        double curr = (double) CAP_FACTOR.getAcquire(this);
        double alpha = (target < curr) ? 0.2 : 0.02;
        double clamped = clampDouble(ewma(curr, target, alpha), 0.15, 1.0);
        CAP_FACTOR.setRelease(this, clamped);
        this.metrics.recordCapFactor(clamped);
    }

    @Override
    public void firstTouch() {
        if (!PRIMED.compareAndSet(this, false, true)) {
            return;
        }

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < this.localCache.partitions(); j++) {
                for (int k = 0; k < chunkSize; k++) {
                    this.localCache.offer(j, DummyFrame.INSTANCE);
                }
            }
        }
        this.localCache.clear();

        CAP_FACTOR.setRelease(this, 1.0);
        TOTAL_COUNT.setOpaque(this, 0);
    }

    public final long getLocalCacheCount() {
        return (long) TOTAL_COUNT.getOpaque(this);
    }

    public final long getMaxLocalCacheCount() {
        double cap = (double) CAP_FACTOR.getAcquire(this);
        return (long) (this.frameQuota * cap);
    }

    @Override
    public final boolean setDownstreamMapping(BitSet active, LatticeEdge[] edges) {
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
    public final int getCore() {
        return this.core;
    }

    public final double getCapFactor() {
        return (double) CAP_FACTOR.getAcquire(this);
    }

    @Override
    public boolean isDrained() {
        return (long) TOTAL_COUNT.getOpaque(this) <= 0;
    }

    @Override
    public void setDrainMode(boolean value) {
        super.setDrain(value);
    }

    private static class CacheTerminal implements LatticeReceiver, Consumer<AbstractFrame> {

        final ControlPlaneCache cpc;

        private final int partitions;
        long framesAdded = 0;

        CacheTerminal(ControlPlaneCache cpc) {
            this.cpc = cpc;
            this.partitions = cpc == null ? 0 : cpc.localCache.partitions();
        }

        @Override
        public void push(AbstractFrame frame) {
            int idx = RoutingFunction.DEFAULT.route(frame, this.partitions);
            while (!this.cpc.localCache.offer(idx, frame)) {
                Thread.onSpinWait();
            }

            TOTAL_COUNT.getAndAddRelease(this.cpc, 1);
        }

        @Override
        public void accept(AbstractFrame frame) {
            this.framesAdded++;

            int idx = RoutingFunction.DEFAULT.route(frame, this.partitions);
            while (!this.cpc.localCache.offer(idx, frame)) {
                Thread.onSpinWait();
            }
        }

        void reset() {
            this.framesAdded = 0;
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
