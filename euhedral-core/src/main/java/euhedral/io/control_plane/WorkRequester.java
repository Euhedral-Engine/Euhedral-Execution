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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

public abstract class WorkRequester extends ControlPlaneCache {

    protected final RequesterState requesterState;
    protected final FlowRecorder executionLatency = new FlowRecorder();
    protected final long lowWaterMark;

    private final PinnedThreadExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    protected volatile Thread cycleThread;
    private FlowRecorder requestLatency = new FlowRecorder();

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

    public void start() {
        if (this.running.compareAndSet(false, true)) {
            this.executor.execute(this::cycle);
        }
    }

    private void cycle() {
        super.register();

        ThreadTools.setTimerResolution(1);
        while (!Thread.interrupted() && this.running.getOpaque()) {
            long bufferCount = this.requesterState.bufferCount.getAcquire();
            if (bufferCount > this.lowWaterMark) {
                Thread.onSpinWait();
                continue;
            }

            if (requestAndPull() <= 0) {
                Thread.onSpinWait();
                continue;
            }

            LockSupport.parkNanos(
                    (long) ((this.requesterState.demandWaitNs - this.requesterState.nowNs) * 0.8));
        }
    }

    protected long drain(long limit) {
        manuallyPull();
        return super.drain(this.requesterState, limit);
    }

    protected long manuallyPull() {
        if (!this.running.getOpaque()) {
            return requestAndPull();
        }
        return 0;
    }

    private long requestAndPull() {
        long cacheCount = super.getCacheCount();
        if (cacheCount != 0) {
            long pull = pullNoDemand();
            if(pull > 0) {
                return pull;
            }
        }

        this.requesterState.refresh();
        this.requesterState.nowNs = System.nanoTime();
        this.requesterState.batchLatencyRecorder.record(this.requesterState.nowNs,
                this.requesterState.nowNs - this.requesterState.drain.lastRecordingTimeNs, false);

        long demand = calculateDemand();
        long pulled = 0;
        if(demand > 0) {
            super.request(demand);
            long now = System.nanoTime();
            this.requestLatency.record(now, now - this.requesterState.nowNs, false);
            this.requesterState.nowNs = now;

            long pull = Math.min(demand, super.getMaxQueueCount() - cacheCount);
            pull = Math.min(pull, getBatchSize() * 4);
            pulled = super.pull(pull);
        } else {
            super.pull(Math.min(getBatchSize(), super.getMaxQueueCount() - cacheCount));
        }

        return pulled;
    }

    private long pullNoDemand() {
        long capacity = super.getMaxQueueCount() - super.getCacheCount();
        long maxFill = Math.min(capacity, getBatchSize());

        return super.pull(maxFill);
    }

    protected abstract long getBatchSize();

    protected abstract void accept(AbstractFrame frame);

    private long calculateDemand() {
        FlowSnapshot drain = this.requesterState.drain;
        FlowSnapshot demandLatency = this.requestLatency.getFlowSnapshot();
        this.requestLatency.refreshSnapshot(demandLatency, false);

        double drainThroughput = drain.throughputNs;
        if(drainThroughput == 0) {
            return this.lowWaterMark;
        }

        double ideal = drainThroughput * (demandLatency.avgUnits);

        return Math.max(0, Math.round(ideal));
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
        public long demandWaitNs = 0;

        public AtomicLong bufferCount = new AtomicLong(0);

        private long nowNs = 0;
        private long windowStartNs = 0;
        private long windowEndNs = 0;

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
