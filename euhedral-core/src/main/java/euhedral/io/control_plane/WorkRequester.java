package euhedral.io.control_plane;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.ThreadTools;
import euhedral.io.config.CacheConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import euhedral.io.utils.MathFunctions;
import euhedral.io.utils.QueueConsumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

public abstract class WorkRequester extends ControlPlaneCache {

    protected final RequesterState requesterState;
    protected final FlowRecorder executionThroughput = new FlowRecorder();
    protected final FlowRecorder executionLatency = new FlowRecorder();
    protected final long lowWaterMark;

    private final PinnedThreadExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    protected volatile Thread cycleThread;

    public WorkRequester(@NonNull CacheConfig cacheConfig, long maxParkNs, PinnedThreadExecutor executor) {
        super(cacheConfig);

        if(super.getCache() == null) {
            this.requesterState = null;
            this.lowWaterMark = 0;
            this.executor = null;
        } else {
            this.requesterState = new RequesterState(this::accept, executor != null, this.executionLatency,
                    super.fillRecorder.getPlain(),
                    super.pullRecorder,
                    super.drainRecorder, maxParkNs);
            this.lowWaterMark = super.getMaxQueueCount() >> 2;
            this.executor = executor;
        }
    }

    protected abstract long getBatchSize();

    protected abstract void accept(AbstractFrame frame);

    public void start() {
        if (this.running.compareAndSet(false, true)) {
            this.executor.execute(this::cycle);
        }
    }

    private void cycle() {
        super.register();

        ThreadTools.setTimerResolution(1);
        while (!Thread.interrupted() && this.running.getOpaque()) {
            if (requestAndPull(getBatchSize()) <= 0) {
                Thread.onSpinWait();
            }
        }
    }

    protected long drain(long limit) {
        return super.drain(this.requesterState, limit);
    }

    protected long manuallyPull() {
        if (!this.running.getOpaque()) {
            long batch = getBatchSize();
            long refill = noDemandPull(batch);
            if(refill != batch) {
                requestAndPull(batch - refill);
            }
        }
        return 0;
    }

    private long requestAndPull(long batchSize) {
        long cacheCount = super.getCacheCount();

        this.requesterState.refresh();
        this.requesterState.nowNs = System.nanoTime();
        this.requesterState.batchLatencyRecorder.record(this.requesterState.nowNs,
                this.requesterState.nowNs - this.requesterState.drain.lastRecordingTimeNs, false);

        long pulled = 0;
        long demand = calculateDemand(batchSize, cacheCount);
        if(demand > 0 && cacheCount < demand) {
            demand = Math.max(demand - cacheCount, batchSize);

            super.request(demand);
            cacheCount = getCacheCount();

            long pull = Math.min(batchSize, super.getMaxQueueCount() - cacheCount);
            pulled = super.pull(pull);
        } else {
            super.pull(Math.min(batchSize, super.getMaxQueueCount() - cacheCount));
        }

        return pulled;
    }

    private long noDemandPull(long limit) {
        long capacity = super.getMaxQueueCount() - super.getCacheCount();
        long maxFill = Math.min(capacity, limit);

        return super.pull(maxFill);
    }

    private long calculateDemand(long batchSize, long cacheCount) {
        FlowSnapshot drain = this.requesterState.drain;
        FlowSnapshot pull = this.requesterState.pull;
        FlowSnapshot throughput = this.executionThroughput.getFlowSnapshot();
        this.executionThroughput.refreshSnapshot(throughput, false);

        double drainThroughput = drain.avgUnitsOverTime;
        if(drainThroughput == 0) {
            return this.lowWaterMark;
        }

        long batch = getBatchSize();

        double multiplier = 1.0;
        if(pull.avgUnits > 0) {
            multiplier = MathFunctions.clampDouble(batch / pull.avgUnits, 1.0, 6.0);
        }

        double ideal = batch * multiplier;
//        if(getCore() == 6) {
//            logger.info("Batch: {} Demand: {} LastPull: {} AvgPull: {} PullVariation: {} PullCV: {} PullThroughput: {}", batch, ideal, pull.lastRecordedUnits, pull.avgUnits, pull.unitVariation, pull.unitCV, pull.avgUnitsOverTime);
//        }
        return Math.max(batchSize, Math.round(ideal));
    }

    @Override
    public void close() {
        if (this.running.compareAndSet(true, false)) {
            this.cycleThread.interrupt();
            LockSupport.unpark(this.cycleThread);

            try {
                this.cycleThread.join(500);
            } catch (Exception ignored) {

            }
            this.executor.close();
            super.close();
        }
    }

    public static class RequesterState extends QueueConsumer {

        public final long maxParkNs;
        public final boolean smt;

        public final FlowRecorder fillRecorder;
        public final FlowRecorder pullRecorder;
        public final FlowRecorder drainRecorder;
        public final FlowRecorder executionLatency;

        public final FlowSnapshot fill;
        public final FlowSnapshot pull;
        public final FlowSnapshot drain;
        public final FlowSnapshot exec;

        public final FlowRecorder batchLatencyRecorder = new FlowRecorder();

        private long nowNs = 0;

        public RequesterState(Consumer<AbstractFrame> consumer, boolean smt, FlowRecorder executionLatency,
                FlowRecorder fillRecorder,
                FlowRecorder pullRecorder,
                FlowRecorder drainRecorder,
                long maxParkNs) {
            super(consumer);
            this.smt = smt;
            this.maxParkNs = maxParkNs;
            this.fillRecorder = fillRecorder;
            this.pullRecorder = pullRecorder;
            this.drainRecorder = drainRecorder;
            this.executionLatency = executionLatency;

            this.fill = fillRecorder.getFlowSnapshot();
            this.pull = pullRecorder.getFlowSnapshot();
            this.drain = drainRecorder.getFlowSnapshot();
            this.exec = executionLatency.getFlowSnapshot();
        }

        public void refresh() {
            this.executionLatency.refreshSnapshot(this.exec, this.smt);
            this.fillRecorder.refreshSnapshot(this.fill, true);
            this.pullRecorder.refreshSnapshot(this.pull, false);
            this.drainRecorder.refreshSnapshot(this.drain, this.smt);
        }
    }
}
