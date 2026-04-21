package euhedral.io;

import static euhedral.io.utils.MathFunctions.clampDouble;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.flow_control.FluxEdge;
import euhedral.io.flow_control.IngestSequencer;
import euhedral.io.flow_control.UpstreamQueue;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.DummyInitFrame;
import euhedral.io.frames.QueueFrame;
import euhedral.io.hardware_utils.CpuCacheSizes;
import euhedral.io.hardware_utils.CpuCacheSizes.CpuCacheLayout;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.interfaces.CacheManager;
import euhedral.io.resource_monitoring.SystemUtilization.CoreSnapshot;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import euhedral.io.utils.ObjectSizer;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("ManualMinMaxCalculation")
public class DRRScheduler extends IngestSequencer implements CacheManager, CloneableObject {

    protected final Logger logger;
    protected final Config config;
    protected final Metrics metrics;
    protected final int coreId;

    protected final AtomicReference<Double> capFactor = new AtomicReference<>(1.0);

    protected Callable<Double> downstreamPressure;

    protected volatile CoreSnapshot snapshot;
    protected volatile long totalBytesCap;

    protected UpstreamQueue upstream;

    public DRRScheduler(@NonNull Config config, @Nullable CoreSnapshot snapshot) {
        this(config, snapshot, () -> 0.0);
    }

    public DRRScheduler(@NonNull Config config, @Nullable CoreSnapshot snapshot,
            @NonNull Callable<Double> downstreamPressure) {
        super(getName(config), config.cloneConfig != null ? config.cloneConfig.coreId() : 0,
                Runtime.getRuntime().availableProcessors() >>> 1, getChunkSize(config.cloneConfig));
        this.logger = LoggerFactory.getLogger(getName(config));
        this.config = config;
        this.snapshot = snapshot;
        this.downstreamPressure = downstreamPressure;
        if (snapshot != null) {
            this.totalBytesCap = snapshot.coreMemoryLimit();
            this.coreId = snapshot.coreId();
        } else {
            this.totalBytesCap = 256 * 1024 * 1024;
            this.coreId = -1;
        }

        this.metrics = new Metrics(config.metricPrefix, coreId, capFactor, totalQueuedSizeBytes,
                config.registry);

    }

    public static String getName(Config config) {
        return config.cloneConfig != null ? config.cloneConfig.shardName() + "-DRRScheduler-"
                                            + config.cloneConfig.coreId() : "DRRScheduler";
    }

    private static int getChunkSize(CloneConfig config) {
        if (config == null) {
            return 512;
        }

        int subQueues = getSubQueueCount(Runtime.getRuntime().availableProcessors() >>> 1);

        CpuCacheLayout layout = CpuCacheSizes.getCacheLayout(config.getCpuSet()[0]);
        long L2 = layout.bytesL2();
        if(config.getCpuSet().length != layout.sharesL2()) {
            L2 /= layout.sharesL2();
        }

        int chunk = (int) Math.min(L2 / subQueues, Integer.MAX_VALUE);
        chunk = (int) (chunk * 0.7);
        chunk = Integer.highestOneBit(chunk);
        chunk /= ObjectSizer.POINTER_SIZE;
        return Math.max(chunk, 512);
    }

    @Override
    public void firstTouch() {
        totalCount.set(0);
        totalQueueWeight = 0;
        totalQueuedSizeBytes.set(0);
        int totalInserts = 3 * chunkSize;
        for (var queue : queueRing) {
            for (int i = 0; i < totalInserts; i++) {
                queue.enqueue(DummyInitFrame.INSTANCE);
            }
            queue.clear();
        }
        Arrays.stream(queueStats).forEach(QueueStats::reset);
        fillRecorder.record(1, true);
        fillBytesRecorder.record(1, false);
        drainRecorder.record(1, false);
        drainBytesRecorder.record(1, false);

        fillRecorder.reset(false);
        fillBytesRecorder.reset(false);
        drainRecorder.reset(false);
        drainBytesRecorder.reset(false);
    }

    @Override
    public void hookOnDrain(long demand) {
        if (demand <= 0) {
            return;
        }

        if (getUpstreamCount() > 0) {
            upstream.pull(demand);
        } else {
            super.request(demand);
        }
    }

    @Override
    protected void refillQueueQuota(QueueStats stats) {
        if (drain.get()) {
            stats.quotaBytes = chunkSize * stats.avgFrameSize.get();
            return;
        }
        stats.quotaBytes = Math.max(0, stats.quotaBytes);

        updateWeight(stats);
        stats.quotaBytes += stats.weight;
        stats.quotaBytes = stats.quotaBytes < 0 ? 1024 * 64 : stats.quotaBytes;
        stats.drainCycles = 0;
    }

    protected void updateWeight(QueueStats stats) {
        long avgSize = stats.avgFrameSize.get();
        if (avgSize < 128) {
            avgSize = 128;
        }

        long targetQuantum = avgSize << 1;
        long currentWeight = stats.weight;

        FlowSnapshot flowSnapshot = fillBytesRecorder.getFlowSnapshot();
        fillBytesRecorder.refreshSnapshot(flowSnapshot, true);
        double cv = flowSnapshot.unitCV;
        double clampedCV = (cv > 0.5) ? 0.5 : (cv < 0.0 ? 0.0 : cv);

        long delta = targetQuantum - currentWeight;
        if (delta < 0) {
            delta = -delta;
        }

        int deadbandShift = (clampedCV > 0.3) ? 2 : 3;

        if (delta > (targetQuantum >> deadbandShift)) {
            stats.weight = smoothWeight(targetQuantum, clampedCV, currentWeight);
        }
    }

    public long smoothWeight(long target, double cv, long weight) {
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
    protected void recordDrainMetrics(QueueFrame queue, QueueStats stats, long drainCount) {
        if (!queue.isEmpty()) {
            if (this.metrics.subQBacklogSummary != null) {
                this.metrics.subQBacklogSummary.record(queue.getSizeBytes());
                this.metrics.subQWeightSummary.record(stats.weight);
            }
        }
    }

    @Override
    public long getUpstreamCount() {
        if (upstream == null) {
            upstream = getThreadUpstreamQueue();
        }
        return upstream.getCachedUpCount();
    }

    @Override
    public long getMaxQueuedBytes() {
        if (snapshot != null) {
            return (long) (snapshot.coreMemoryLimit() * 0.8);
        }
        return (long) (Runtime.getRuntime().maxMemory() * 0.8);
    }

    @Override
    public void close() {
        metrics.close();
        super.close();
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.snapshot = snapshot;
        this.totalBytesCap = snapshot.coreMemoryLimit();
        double currentPressure;
        try {
            currentPressure = clampDouble(this.downstreamPressure.call(), 0.0, 1.0);
        } catch (Exception ignored) {
            currentPressure = 1.0;
        }

        double target = 1.0 - (0.85 * currentPressure);

        capFactor.updateAndGet(curr -> {
            // Fast Drop (0.2), Slow Rise (0.02)
            double alpha = (target < curr) ? 0.2 : 0.02;
            return clampDouble((curr * (1.0 - alpha)) + (target * alpha), 0.15, 1.0);
        });
    }

    @Override
    public Publisher<? extends AbstractFrame> process(
            Publisher<? extends AbstractFrame> frameFlux) {
        ingest(frameFlux);
        return output();
    }

    @Override
    public void ingest(Publisher<? extends AbstractFrame> frameFlux) {
        if (frameFlux instanceof FluxEdge dh) {
            onSubscribe(dh);
        } else {
            frameFlux.subscribe(this);
        }
    }

    @Override
    public DRRScheduler output() {
        return this;
    }

    @Override
    public boolean isDrained() {
        return totalCount.get() == 0;
    }

    @Override
    public void pull(DrainBuffer buffer, long demand) {
        if (upstream == null) {
            upstream = getThreadUpstreamQueue();
        }
        if (upstream.getCachedUpCount() > 0) {
            upstream.pull(buffer, demand);
        } else {
            super.pull(buffer, demand);
        }
    }

    @Override
    public void setDrainMode(boolean value) {
        super.drain.set(value);
    }

    @Override
    public void setDownstreamPressureMonitor(Callable<Double> pressure) {
        this.downstreamPressure = pressure;
    }

    @Override
    public DRRScheduler clone(CloneConfig cloneConfig) {
        return new DRRScheduler(config.clone(cloneConfig), snapshot);
    }

    public static class Metrics implements AutoCloseable {

        public final MeterRegistry registry;

        public final DistributionSummary subQBacklogSummary;
        public final DistributionSummary subQWeightSummary;

        private final List<Meter> meters = new ArrayList<>();

        public Metrics(String metricPrefix, int coreId, AtomicReference<Double> capFactor,
                AtomicLong totalQueuedSizeBytes, MeterRegistry registry) {
            this.registry = registry;
            if (registry != null) {
                String tag = String.valueOf(coreId);

                subQBacklogSummary =
                        DistributionSummary.builder(metricPrefix + ".drr_sub_queue_backlog_bytes")
                                .description("Amount of bytes stored in a sub queue")
                                .tag("core", tag).publishPercentiles(0.5, 0.95, 0.99)
                                .register(registry);

                subQWeightSummary =
                        DistributionSummary.builder(metricPrefix + ".drr_sub_queue_weight")
                                .tag("core", tag).publishPercentiles(0.0, 1.0).register(registry);

                meters.add(
                        Gauge.builder(metricPrefix + ".cap_factor", capFactor, AtomicReference::get)
                                .description(
                                        "Current buffer capacity multiplier. Higher is better. (0.15 to 1.0)")
                                .tag("core", tag).register(registry));

                meters.add(Gauge.builder(metricPrefix + ".drr_backlog",
                                () -> totalQueuedSizeBytes.get() / 1024)
                        .description("Total bytes currently buffered in all sub queues of the DRR")
                        .baseUnit("KB").register(registry));
            } else {
                subQBacklogSummary = null;
                subQWeightSummary = null;
            }
        }

        @Override
        public void close() {
            meters.forEach(Meter::close);
            meters.clear();
            if (subQBacklogSummary != null) {
                subQBacklogSummary.close();
                subQWeightSummary.close();
            }
        }
    }

    public record Config(CloneConfig cloneConfig, int maxSubQueues, String metricPrefix,
                         MeterRegistry registry) implements CloneableObject {

        @Override
        public Config clone(CloneConfig cloneConfig) {
            String metricPrefix = metricPrefix();
            if (cloneConfig != null) {
                int cpuId = cloneConfig.coreId();
                metricPrefix = cloneConfig.metricPrefix() + "-" + cloneConfig.shardName()
                        + "-DRRScheduler-" + cpuId;
            }
            return new Config(cloneConfig, maxSubQueues, metricPrefix, registry);
        }

        @Override
        public void close() throws Exception {

        }
    }
}
