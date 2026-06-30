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

        long demand = getBatchSize() * 2;

        long upCache = super.getUpstreamCacheCount();
        long maxLocalCache = super.getMaxQueueCount();
        long localCache = super.getCacheCount();

        long pull = demand;
        pull = Math.min(pull, maxLocalCache - localCache);

        long totalStored = upCache + localCache;
        if(totalStored > maxLocalCache || localCache >= demand) {
            demand = 0;
        }

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

        final FlowPredictor pullPredictor = new FlowPredictor(128, 0.05, true);

        public final FlowSnapshot fill;
        public final FlowSnapshot pull;

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
