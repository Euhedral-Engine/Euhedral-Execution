package euhedral.common.io.dispatch;

import euhedral.common.io.dispatch.control_plane.CloneConfig;
import euhedral.common.io.dispatch.flow_control.DemandCoordinator.FluxEdge;
import euhedral.common.io.dispatch.flow_control.IngestSequencer;
import euhedral.common.io.dispatch.frames.AbstractFrame;
import euhedral.common.io.dispatch.frames.QueueFrame;
import euhedral.common.io.dispatch.interfaces.CloneableObject;
import euhedral.common.io.dispatch.interfaces.DispatchPreProcess;
import euhedral.common.io.dispatch.utils.FlowRecorder;
import euhedral.common.io.dispatch.utils.NumaMapper;
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

    protected final FlowRecorder starvationRecorder = new FlowRecorder();
    protected final AtomicReference<Double> capFactor = new AtomicReference<>(1.0);

    protected Callable<Double> downstreamPressure;

    protected volatile CoreSnapshot snapshot;
    protected volatile double pressure;
    protected volatile long totalBytesCap;

    protected volatile boolean drainMode = false;
    protected volatile int systemCoreCount = 1;
    protected long lastRequestNs = 0;
    protected int lastUpstreamCount = 0;
    protected int drainCount = 0;
    protected int baseMask = 7;
    protected long adaptiveMask = 7;

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
    protected void hookOnDrain(int totalDrain) {
        long nowNs = System.nanoTime();

        int upCount = getThreadUpstreamCount();
        if (upCount > 0) {
            starvationRecorder.record(nowNs, totalDrain == 0 ? 1 : 0);
        }

        long effectiveCount = fillRecorder.getEffectiveMeasurementWindowCount(nowNs);

        long minWindowCount = 32;
        if (effectiveCount >= minWindowCount) {
            if (totalDrain > 0 && (drainCount & adaptiveMask) == 0) {
                return;
            }
        }


        long avgFill = fillRecorder.getAverageUnits();
        long avgDrain = Math.max(batchRecorder.getAverageUnits(), 1);

        double fillDrainRatio = (double) avgFill / avgDrain;

        double starvationLevel = starvationRecorder.getRollingAveragePerRecording(nowNs);
        int starvationBoost = (int) Math.min(starvationLevel * 16, 16);
        int fillPenalty = (fillDrainRatio >= 1.0)
                ? 0
                : (int) ((1.0 - fillDrainRatio) * 4.0);

        long newMask = Math.min(63, baseMask + starvationBoost + fillPenalty);
        adaptiveMask = (adaptiveMask * 3 + newMask) >> 2;

        long original = getThreadDemand();

        long consumed = totalDrain > original ? original : totalDrain;
        long demand = addAndGetThreadDemand(-consumed);
        long avgFramesPerBatch = Math.max(batchRecorder.getAverageUnits(), 1);

        if (avgFramesPerBatch <= 1 && upCount == 0) {
            lastUpstreamCount = upCount;

            if (demand > 0) {
                pull();
            } else {
                long init = Math.min(1024, Math.max(1, (long) (totalBytesCap * 0.01 / 1024)));
                request(init);

                addAndGetThreadDemand(init);
            }
            return;
        }

        long queueSize = totalCount.get();
        long queueCount = Math.max(demand + queueSize, 1);

        int log = calculateLog2((int) Math.min(queueCount, Integer.MAX_VALUE));

        long alpha = Math.max(3, 3L * log);
        long beta = Math.max(6, 6L * log);
        long queueEst = getVegasEstimate();

        long pacingNs = getRequestPacingNs(alpha, beta, queueEst);

        if (nowNs - lastRequestNs < pacingNs) {
            return;
        }

        long avgBytesPerBatch = Math.max(1, bytesPerBatchRecorder.getAverageUnits());
        long avgFrameSize = Math.max(64, avgBytesPerBatch / avgFramesPerBatch);

        long lastInterval = batchRecorder.getLastInterval();
        long lookaheadNs = Math.clamp(lastInterval << 1, 1_000_000L, 50_000_000L); // 1ms - 50ms

        long fillPrediction = fillRecorder.estimateFutureUnits(lookaheadNs);
        long drainPrediction = batchRecorder.estimateFutureUnits(lookaheadNs);

        long framesNeeded = Math.min(drainPrediction, fillPrediction);

        if (queueEst < alpha) {
            framesNeeded += (framesNeeded >> 2);
        }

        double starvationFactor = Math.clamp(starvationRecorder.getRollingAveragePerRecording(nowNs),
                    0.5, 3.0);

        if (upCount < lastUpstreamCount) {
            starvationFactor *= 1.25;
        }

        long maxBytes = (long) (totalBytesCap * capFactor.get());
        long actualBytes = totalQueuedSizeBytes.get();

        long byteQuota = Math.max(0, maxBytes - actualBytes);
        long frameQuota = byteQuota / avgFrameSize;

        long finalRequest = (long) Math.min(demand + framesNeeded * starvationFactor, frameQuota);

        if (snapshot != null) {
            long projected = finalRequest * avgFrameSize + snapshot.globalBytesUsed();
            if (finalRequest > 0 && projected > snapshot.globalMemoryLimit() * 0.8) {
                pull();
                return;
            }
        }

        if (finalRequest > 0) {
            lastUpstreamCount = upCount;
            lastRequestNs = nowNs;
            request(finalRequest);
        } else if(finalRequest == 0 && upCount > 0) {
            request(1);
        }else if (demand > 0) {
            pull();
        }
    }

    protected long getVegasEstimate() {
        long queueCount = Math.max(getThreadDemand() + totalCount.get(), 1);
        return batchRecorder.getVegasQueueEstimate(queueCount, 1);
    }

    private int calculateLog2(int current) {
        int log2 = 31 - Integer.numberOfLeadingZeros(Math.max(current, 1));

        return Math.max(3, log2);
    }

    protected long getRequestPacingNs(long alpha, long beta, long queueEst) {
        long pacingNs = Math.clamp(
                batchRecorder.getThroughputNs(),
                1_000_000L,
                50_000_000L
        );

        if (queueEst < alpha) {
            pacingNs -= pacingNs >> 3;
        } else if (queueEst > beta) {
            pacingNs += pacingNs >> 2;
        }
        return Math.clamp(pacingNs, 1_000_000L, 50_000_000L);
    }

    protected int getQueueQuota(QueueFrame node) {
        if ((node.getDrainCount() & 256) == 0) {
            updateQuantum(node);
        }

        long quota = node.getQuota() + node.getWeight();
        node.setQuota(quota < 0 ? Long.MAX_VALUE : quota);

        long avgSize = Math.max(1, node.getAvgFrameSize().get());

        long limit = quota / avgSize;
        node.setQuota(quota - (avgSize * limit));

        int burstLimit = (int) Math.max(32, limit * 2);
        burstLimit = drainMode ? node.getQueueCount() : burstLimit;
        return burstLimit;
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
        systemCoreCount = NumaMapper.INSTANCE.getSystemTopology().effectiveCpus().get()
                .cardinality();

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
