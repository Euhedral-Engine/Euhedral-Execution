package euhedral.common.io.dispatch;

import euhedral.common.io.dispatch.control_plane.CloneConfig;
import euhedral.common.io.dispatch.flow_control.DemandCoordinator.FluxEdge;
import euhedral.common.io.dispatch.flow_control.DemandCoordinator.UpstreamQueue;
import euhedral.common.io.dispatch.flow_control.IngestSequencer;
import euhedral.common.io.dispatch.frames.AbstractFrame;
import euhedral.common.io.dispatch.frames.QueueFrame;
import euhedral.common.io.dispatch.interfaces.CloneableObject;
import euhedral.common.io.dispatch.interfaces.DispatchPreProcess;
import euhedral.common.io.dispatch.utils.FlowRecorder;
import euhedral.common.io.dispatch.utils.SystemUtilization.CoreSnapshot;
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
import org.springframework.util.unit.DataSize;

@SuppressWarnings("ManualMinMaxCalculation")
public class DRRScheduler extends IngestSequencer implements DispatchPreProcess, CloneableObject {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());
    protected final Config config;
    protected final Metrics metrics;
    protected final int coreId;

    protected final AtomicReference<Double> capFactor = new AtomicReference<>(1.0);

    protected Callable<Double> downstreamPressure;

    protected volatile CoreSnapshot snapshot;
    protected volatile double pressure;
    protected volatile long totalBytesCap;

    protected volatile boolean drainMode = false;
    protected long lastRequestNs = 0;


    public DRRScheduler(@NonNull Config config, @Nullable CoreSnapshot snapshot) {
        this(config, snapshot, () -> 0.0);
    }

    public DRRScheduler(@NonNull Config config, @Nullable CoreSnapshot snapshot,
            @NonNull Callable<Double> downstreamPressure) {
        this.config = config;
        this.downstreamPressure = downstreamPressure;
        if (snapshot != null) {
            this.totalBytesCap = snapshot.coreMemoryLimit();
            this.coreId = snapshot.coreId();
        } else {
            this.totalBytesCap = DataSize.ofMegabytes(256).toBytes();
            this.coreId = -1;
        }

        String name = config.cloneConfig != null ? config.cloneConfig.shardName() + "-DRRScheduler-"
                + config.cloneConfig.coreId() : "DRRScheduler";

        super(name, config.maxSubQueues);

        this.metrics = new Metrics(config.metricPrefix, coreId, capFactor, totalQueuedSizeBytes,
                () -> pressure, config.registry);

    }

    @Override
    public Publisher<AbstractFrame> process(Publisher<AbstractFrame> frameFlux) {
        ingest(frameFlux);
        return output();
    }

    @Override
    public void ingest(Publisher<AbstractFrame> frameFlux) {
        if (frameFlux instanceof FluxEdge dh) {
            onSubscribe(dh);
        } else {
            frameFlux.subscribe(this);
        }
    }

    @Override
    public Publisher<AbstractFrame> output() {
        return this;
    }

    @Override
    protected void hookOnDrain(long demand) {
        long nowNs = System.nanoTime();

        long avgFramesPerBatch = Math.max(batchRecorder.getAverageUnits(), 1);
        long avgBytesPerBatch = bytesPerBatchRecorder.getAverageUnits();
        long avgFrameSize = avgBytesPerBatch == 0 ? 1024 : Math.max(64, avgBytesPerBatch / avgFramesPerBatch);

        long maxBytes = (long) (totalBytesCap * capFactor.get());
        long actualBytes = totalQueuedSizeBytes.get();

        long byteQuota = Math.max(0, maxBytes - actualBytes);
        long frameQuota = byteQuota / avgFrameSize;

        long safeDemand = Math.max(0, demand);
        long finalRequest = Math.min(safeDemand, frameQuota);

        if (snapshot != null) {
            long hardCap = (long) (snapshot.globalMemoryLimit() * 0.8);
            long projected = finalRequest * avgFrameSize + snapshot.globalBytesUsed();
            long layerWidth = getLayerWidth();
            if (finalRequest > 0 && projected > hardCap) {
                finalRequest = (hardCap - snapshot.globalBytesUsed()) / (avgFrameSize * layerWidth);
            }
        }

        if (finalRequest > 0) {
            lastRequestNs = nowNs;
            request(finalRequest);
        } else {
            lastRequestNs = nowNs;
            request(64);
        }
    }

    protected int refillQueueQuota(QueueFrame node) {
        long quota = Math.max(0, node.getQuota() - node.getDrainCycles());
        node.setQuota(quota);

        if (quota > 0) {
            return (int) quota;
        }

        updateQuantum(node);
        quota = node.getQuota() + node.getWeight();
        node.setQuota(quota < 0 ? Long.MAX_VALUE : quota);

        long avgSize = Math.max(1, node.getAvgFrameSize().get());

        long recentFrames = Math.max(1, batchRecorder.getRollingSum());
        long recentBytes = Math.max(1, bytesPerBatchRecorder.getRollingSum());

        long proportionalQuota = (recentFrames * avgSize) + (recentBytes / 2);

        long scaledQuota = Math.max(64, proportionalQuota);

        scaledQuota = scaledQuota * node.getWeight() / Math.max(1, totalQueueWeight);

        node.setQuota(scaledQuota);

        return drainMode ? node.getQueueCount() : (int) (scaledQuota / avgSize);
    }

    protected void updateQuantum(QueueFrame node) {
        long avgSize = node.getAvgFrameSize().get();
        if (avgSize < 128) {
            avgSize = 128;
        }

        long targetQuantum = avgSize << 1;
        long currentWeight = node.getWeight();

        double cv = bytesPerBatchRecorder.getUnitCV();
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
    public void update(CoreSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.snapshot = snapshot;
        this.totalBytesCap = snapshot.coreMemoryLimit();
        double currentPressure;
        try {
            currentPressure = Math.clamp(this.downstreamPressure.call(), 0.0, 1.0);
        } catch (Exception ignored) {
            currentPressure = 1.0;
        }

        double target = 1.0 - (0.85 * currentPressure);

        capFactor.updateAndGet(curr -> {
            // Fast Drop (0.2), Slow Rise (0.02)
            double alpha = (target < curr) ? 0.2 : 0.02;
            return Math.clamp((curr * (1.0 - alpha)) + (target * alpha), 0.15, 1.0);
        });
    }

    @Override
    protected long getMaxQueueCount() {
        long avgFramesPerBatch = Math.max(batchRecorder.getAverageUnits(), 1);
        long avgBytesPerBatch = bytesPerBatchRecorder.getAverageUnits();
        long avgFrameSize = avgBytesPerBatch == 0 ? 1024 : Math.max(64, avgBytesPerBatch / avgFramesPerBatch);

        long maxBytes = (long) (totalBytesCap * capFactor.get());
        long actualBytes = totalQueuedSizeBytes.get();

        long byteQuota = Math.max(0, maxBytes - actualBytes);
        return byteQuota / avgFrameSize;
    }

    @Override
    public void setDrainMode(boolean value) {
        this.drainMode = value;
    }

    @Override
    public void setDownstreamPressureMonitor(Callable<Double> pressure) {
        this.downstreamPressure = pressure;
    }

    @Override
    public DRRScheduler clone(CloneConfig cloneConfig) {
        return new DRRScheduler(config.clone(cloneConfig), snapshot);
    }

    @Override
    public boolean isDrained() {
        return totalQueuedSizeBytes.get() == 0;
    }

    @Override
    public void close() {
        metrics.close();
        super.close();
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
                                () -> DataSize.ofBytes(totalQueuedSizeBytes.get()).toKilobytes())
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
