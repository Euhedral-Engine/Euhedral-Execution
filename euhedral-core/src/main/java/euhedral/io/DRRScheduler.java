package euhedral.io;

import static euhedral.io.utils.MathFunctions.clampDouble;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.flow_control.FluxEdge;
import euhedral.io.flow_control.IngestSequencer;
import euhedral.io.flow_control.UpstreamQueue;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.DummyInitFrame;
import euhedral.io.frames.QueueFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.interfaces.DispatchPreProcess;
import euhedral.io.resource_monitoring.SystemUtilization.CoreSnapshot;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("ManualMinMaxCalculation")
public class DRRScheduler extends IngestSequencer implements DispatchPreProcess, CloneableObject {

    protected final Logger logger;
    protected final Config config;
    protected final Metrics metrics;
    protected final int coreId;

    protected final AtomicReference<Double> capFactor = new AtomicReference<>(1.0);

    protected Callable<Double> downstreamPressure;

    protected volatile CoreSnapshot snapshot;
    protected volatile double pressure;
    protected volatile long totalBytesCap;

    protected UpstreamQueue upstream;

    public DRRScheduler(@NonNull Config config, @Nullable CoreSnapshot snapshot) {
        this(config, snapshot, () -> 0.0);
    }

    public DRRScheduler(@NonNull Config config, @Nullable CoreSnapshot snapshot,
            @NonNull Callable<Double> downstreamPressure) {
        super(getName(config), config.maxSubQueues);
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
                () -> pressure, config.registry);

    }

    public static String getName(Config config) {
        return config.cloneConfig != null ? config.cloneConfig.shardName() + "-DRRScheduler-"
                                            + config.cloneConfig.coreId() : "DRRScheduler";
    }

    @Override
    public void firstTouch() {
        totalCount.set(0);
        totalQueueWeight = 0;
        totalQueuedSizeBytes.set(0);

        int totalInserts = 3 * CHUNK_SIZE;
        for (var queue : queueRing) {
            for (int i = 0; i < totalInserts; i++) {
                queue.enqueue(DummyInitFrame.INSTANCE);
            }
            queue.clear();
        }
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

    protected int refillQueueQuota(QueueFrame node) {
        long quota = Math.max(0, node.getQuota() - node.getDrainCycles() * config.maxSubQueues);
        node.setQuota(quota);

        if (quota > 0) {
            return (int) quota;
        }

        updateQuantum(node);
        quota = node.getQuota() + node.getWeight();
        node.setQuota(quota < 0 ? Long.MAX_VALUE : quota);

        long avgSize = Math.max(1, node.getAvgFrameSize().get());

        long recentFrames = Math.max(1, drainRecorder.getRollingSum());
        long recentBytes = Math.max(1, fillBytesRecorder.getRollingSum());

        long proportionalQuota = (recentFrames * avgSize) + (recentBytes / 2);

        long scaledQuota = Math.max(64, proportionalQuota);
        scaledQuota = scaledQuota * node.getWeight() / Math.max(1, totalQueueWeight);
        scaledQuota = Math.max(64, scaledQuota);

        node.setDrainCycles(0);
        node.setQuota(scaledQuota);

        return drain.get() ? node.getQueueCount() : (int) (scaledQuota / avgSize);
    }

    protected void updateQuantum(QueueFrame node) {
        long avgSize = node.getAvgFrameSize().get();
        if (avgSize < 128) {
            avgSize = 128;
        }

        long targetQuantum = avgSize << 1;
        long currentWeight = node.getWeight();

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
            node.smoothWeight(targetQuantum, clampedCV);
            if (this.metrics.nodeWeightVarianceSummary != null) {
                this.metrics.nodeWeightVarianceSummary.record(node.getWeight());
            }
        }
    }

    @Override
    protected void recordDrainMetrics(QueueFrame queue, long drainCount) {
        if (!queue.isEmpty()) {
            if (this.metrics.nodeBacklogSummary != null) {
                this.metrics.nodeBacklogSummary.record(queue.getSizeBytes());
            }
        }
    }

    @Override
    public long getUpstreamCount() {
        if(upstream == null) {
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

        public final DistributionSummary nodeBacklogSummary;
        public final DistributionSummary nodeWeightVarianceSummary;

        private final List<Meter> meters = new ArrayList<>();

        public Metrics(String metricPrefix, int coreId, AtomicReference<Double> capFactor,
                AtomicLong totalQueuedSizeBytes, Supplier<Double> pressure,
                MeterRegistry registry) {
            this.registry = registry;
            if (registry != null) {
                String tag = String.valueOf(coreId);

                nodeBacklogSummary = DistributionSummary.builder(
                                metricPrefix + ".node_backlog_bytes")
                        .tag("core", tag)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry);

                nodeWeightVarianceSummary = DistributionSummary.builder(
                                metricPrefix + ".node_size_variance")
                        .tag("core", tag)
                        .publishPercentiles(0.0, 1.0)
                        .register(registry);

                meters.add(
                        Gauge.builder(metricPrefix + ".cap_factor", capFactor, AtomicReference::get)
                                .description(
                                        "Current buffer capacity multiplier. Higher is better. (0.15 to 1.0)")
                                .tag("core", tag)
                                .register(registry));

                meters.add(Gauge.builder(metricPrefix + ".queue_kb",
                                () -> totalQueuedSizeBytes.get() / 1024)
                        .description("Total KiloBytes currently buffered in the DRR ready queue")
                        .baseUnit("KB")
                        .register(registry));

                meters.add(Gauge.builder(metricPrefix + ".pressure", pressure)
                        .description("Combined Hardware + Downstream pressure signal")
                        .tag("core", tag)
                        .register(registry));
            } else {
                nodeBacklogSummary = null;
                nodeWeightVarianceSummary = null;
            }
        }

        @Override
        public void close() {
            meters.forEach(Meter::close);
            meters.clear();
            if (nodeBacklogSummary != null) {
                nodeBacklogSummary.close();
                nodeWeightVarianceSummary.close();
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
                metricPrefix =
                        cloneConfig.metricPrefix() + "-" + cloneConfig.shardName()
                                + "-DRRScheduler-"
                                + cpuId;
            }
            return new Config(cloneConfig, maxSubQueues, metricPrefix,
                    registry);
        }

        @Override
        public void close() throws Exception {

        }
    }
}
