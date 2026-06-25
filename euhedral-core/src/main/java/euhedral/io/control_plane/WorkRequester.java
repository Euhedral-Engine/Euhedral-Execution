package euhedral.io.control_plane;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.ThreadTools;
import euhedral.io.config.CacheConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.FlowPredictor;
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

    protected final FlowPredictor requestPredictor = new FlowPredictor(128, 0.990);

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
            this.requesterState = new RequesterState(this::accept, executor != null,
                    super.fillRecorder.getPlain(),
                    super.pullRecorder, maxParkNs);
            this.lowWaterMark = super.getMaxQueueCount() >> 2;
            this.executor = executor;
        }
    }

    protected abstract long getBatchSize();

    protected abstract void accept(AbstractFrame frame);

    protected void updateRequestSize(double measuredThroughput) {
        long requestSize = this.requesterState.requestSize;
        this.requestPredictor.record(requestSize, measuredThroughput);

        double stdDev = this.requestPredictor.stdDev(requestSize);
        double mean = this.requestPredictor.mean(requestSize);

        double exploration = MathFunctions.clampDouble(2.0 * stdDev / Math.max(mean, 1e-6), 0.01, 0.25);

        double step = Math.max(4, Math.sqrt(stdDev));
        if(!Double.isFinite(step)) {
            step = 4;
        }

        double predictedNext = this.requestPredictor.computeNextBestX(requestSize, step, exploration);
        this.requesterState.requestSize = Math.max(Math.round(predictedNext), 4L);
    }

    public void start() {
        if (this.running.compareAndSet(false, true)) {
            this.executor.execute(this::cycle);
        }
    }

    private void cycle() {
        super.register(getCore());

        ThreadTools.setTimerResolution(1);
        while (!Thread.interrupted() && this.running.getOpaque()) {
            if (requestAndPull() <= 0) {
                Thread.onSpinWait();
            }
        }
    }

    protected long drain(long limit) {
        return super.drain(this.requesterState, limit);
    }

    protected long manuallyPull() {
        if (!this.running.getOpaque()) {
            requestAndPull();
        }
        return 0;
    }

    private long requestAndPull() {
        this.requesterState.refresh();

        long demand = this.requesterState.requestSize;

        double pullT = this.requesterState.pull.avgUnitsOverTime;
        double fillT = this.requesterState.fill.avgUnitsOverTime;

        long pull = demand;
        if(fillT > 0) {
            pull = (long) Math.ceil(pull * pullT / (pullT + fillT));
        }
        pull = Math.min(pull, super.getMaxQueueCount() - super.getCacheCount());

        long upCache = super.getUpstreamCacheCount();

        double pressure =
                demand / Math.max(1.0, upCache / (double) super.getThreadCount());
        pressure = Math.min(1.0, pressure);

        demand = Math.round(demand * pressure);

        super.request(demand);
        return super.pull(pull);
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

        public final FlowSnapshot fill;
        public final FlowSnapshot pull;

        public long requestSize = 4;

        public RequesterState(Consumer<AbstractFrame> consumer, boolean smt,
                FlowRecorder fillRecorder,
                FlowRecorder pullRecorder,
                long maxParkNs) {
            super(consumer);
            this.smt = smt;
            this.maxParkNs = maxParkNs;
            this.fillRecorder = fillRecorder;
            this.pullRecorder = pullRecorder;

            this.fill = fillRecorder.getFlowSnapshot();
            this.pull = pullRecorder.getFlowSnapshot();
        }

        public void refresh() {
            this.fillRecorder.refreshSnapshot(this.fill, true);
            this.pullRecorder.refreshSnapshot(this.pull, false);
        }
    }
}
