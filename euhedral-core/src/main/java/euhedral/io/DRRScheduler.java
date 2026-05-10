package euhedral.io;

import static euhedral.io.utils.MathFunctions.clampDouble;
import static euhedral.io.utils.MathFunctions.ewma;

import euhedral.atomics.AtomicDouble;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuCacheLayout;
import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.DRRConfig;
import euhedral.io.flow_control.FluxEdge;
import euhedral.io.flow_control.IngestSequencer;
import euhedral.io.flow_control.UpstreamQueue;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.DummyInitFrame;
import euhedral.io.frames.QueueFrame;
import euhedral.io.interfaces.CacheManager;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.metrics.DRRMetrics;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import euhedral.io.utils.MathFunctions;
import euhedral.io.utils.ObjectSizer;
import java.util.Arrays;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DRRScheduler extends IngestSequencer implements CacheManager, CloneableObject {

    protected final Logger logger;
    protected final DRRConfig config;
    protected final DRRMetrics metrics;
    protected final int coreId;

    protected final AtomicDouble capFactor = new AtomicDouble(1d);

    protected Callable<Double> downstreamPressure;

    protected volatile CoreSnapshot snapshot;
    protected volatile long totalBytesCap;

    protected UpstreamQueue upstream;

    public DRRScheduler(@NonNull DRRConfig config, @Nullable CoreSnapshot snapshot) {
        this(config, snapshot, () -> 0.0);
    }

    public DRRScheduler(@NonNull DRRConfig config, @Nullable CoreSnapshot snapshot,
            @NonNull Callable<Double> downstreamPressure) {
        super(getName(config), config.cloneConfig() != null ? config.cloneConfig().coreId() : 0,
                Runtime.getRuntime().availableProcessors() >>> 1, getChunkSize(config.cloneConfig()));
        this.logger = LoggerFactory.getLogger(getName(config));
        this.config = config;
        this.snapshot = snapshot;
        this.downstreamPressure = downstreamPressure;
        if (snapshot != null) {
            this.totalBytesCap = snapshot.memoryLimit();
            this.coreId = snapshot.coreId();
        } else {
            this.totalBytesCap = 256 * 1024 * 1024;
            this.coreId = -1;
        }

        this.metrics = new DRRMetrics(config.metricPrefix(), coreId, capFactor, totalQueuedSizeBytes,
                config.registry());

    }

    public static String getName(DRRConfig config) {
        return config.cloneConfig() != null ? config.cloneConfig().shardName() + "-DRRScheduler-"
                                            + config.cloneConfig().coreId() : "DRRScheduler";
    }

    private static int getChunkSize(CloneConfig config) {
        if (config == null) {
            return 512;
        }

        int subQueues = getSubQueueCount(Runtime.getRuntime().availableProcessors() >>> 1);

        CpuCacheLayout layout = SystemInfo.getCacheLayout(config.getCpuSet()[0]);
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
        int totalInserts = 3 * chunkSize;
        for (var queue : queueRing) {
            for (int i = 0; i < totalInserts; i++) {
                queue.enqueue(DummyInitFrame.INSTANCE);
            }
            queue.clear();
        }
        Arrays.stream(queueStats).forEach(QueueStats::reset);
        fillRecorder.getAcquire().record(1, true);
        fillBytesRecorder.getAcquire().record(1, false);
        drainRecorder.getAcquire().record(1, false);
        drainBytesRecorder.getAcquire().record(1, false);

        fillRecorder.getAcquire().reset(false);
        fillBytesRecorder.getAcquire().reset(false);
        drainRecorder.getAcquire().reset(false);
        drainBytesRecorder.getAcquire().reset(false);

        totalCount.set(0);
        totalQueueWeight = 0;
        totalQueuedSizeBytes.set(0);
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

        FlowSnapshot flowSnapshot = fillBytesRecorder.getAcquire().getFlowSnapshot();
        fillBytesRecorder.getAcquire().refreshSnapshot(flowSnapshot, true);
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
        double cap = Math.min(0.8, this.capFactor.getAcquire());
        if (snapshot != null) {
            return (long) (snapshot.memoryLimit() * cap);
        }
        return (long) (Runtime.getRuntime().maxMemory() * cap);
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
        this.totalBytesCap = snapshot.memoryLimit();
        double pressure;
        try {
            pressure = clampDouble(this.downstreamPressure.call(), 0.0, 1.0);
        } catch (Throwable ignored) {
            pressure = 1.0;
        }

        double target = 1.0 - (0.85 * pressure);

        double curr = capFactor.getPlain();
        double alpha = (target < curr) ? 0.2 : 0.02;
        double clamped = clampDouble(ewma(curr, target, alpha), 0.15, 1.0);
        capFactor.setRelease(clamped);
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
}
