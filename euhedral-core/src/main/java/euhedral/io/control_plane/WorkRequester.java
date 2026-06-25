package euhedral.io.control_plane;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.ThreadTools;
import euhedral.io.config.CacheConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import euhedral.io.utils.QueueConsumer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

public abstract class WorkRequester extends ControlPlaneCache {

    protected final RequesterState requesterState;
    protected final FlowRecorder upstreamCache = new FlowRecorder(Duration.ofNanos(100_000), Duration.ofMillis(10));
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

        long demand = calculateDemand();

        double pullT = this.requesterState.pull.avgUnitsOverTime;
        double fillT = this.requesterState.fill.avgUnitsOverTime;
        double totalT = pullT + fillT;

        long pull = getBatchSize();
        if(totalT > 0) {
            pull = (long) Math.ceil(pull * 0.5 * pullT / totalT);
        }
        pull = Math.min(pull, super.getMaxQueueCount() - super.getCacheCount());

        super.request(demand);
        return super.pull(pull);
    }

    private long calculateDemand() {
        FlowSnapshot pull = this.requesterState.pull;
        FlowSnapshot fill = this.requesterState.fill;
        FlowSnapshot execThroughput = this.executionThroughput.getFlowSnapshot();
        this.executionThroughput.refreshSnapshot(execThroughput, false);

        double avgReplenish = pull.avgUnits + fill.avgUnitsOverTime * Math.max(pull.avgInterval, fill.avgInterval);
        if(avgReplenish == 0) {
            return getBatchSize();
        }

        long upCache = super.getUpstreamCacheCount();
        this.upstreamCache.record(upCache, false);

        FlowSnapshot totalUpstreamCache = this.upstreamCache.getFlowSnapshot();
        this.upstreamCache.refreshSnapshot(totalUpstreamCache, false);

        long coWorkers = super.getThreadCount();

        long request = (long) execThroughput.avgUnits;
        request = Math.max(request, 4);

        if(request * coWorkers < upCache) {
            request = 0;
        } else if(totalUpstreamCache.unitTrend < 0) {
            request += Math.max(Math.round(Math.sqrt(request)), 4);
        }

        if(getCore() == 6) {
            logger.info("AvgPull: {} PullInterval: {} ExecThroughput: {} ExecInterval: {} Request: {} AvgCache: {} CacheTrend: {}", pull.avgUnits, pull.avgInterval, execThroughput.avgUnits, execThroughput.avgInterval, request, totalUpstreamCache.avgUnits, totalUpstreamCache.unitTrend);
        }
        return request;
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

        private long requestSize = 4;

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
