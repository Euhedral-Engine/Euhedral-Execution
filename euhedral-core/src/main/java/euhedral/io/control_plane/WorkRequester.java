package euhedral.io.control_plane;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.ThreadTools;
import euhedral.io.config.CacheConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.FlowRecorder;
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
            this.requesterState = new RequesterState(this::accept, executor != null, maxParkNs);
            this.lowWaterMark = super.getMaxQueueCount() >> 2;
            this.executor = executor;
        }
    }

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
            if (requestAndPull() == 0) {
                Thread.onSpinWait();
            }
        }
    }

    protected long drain(long limit) {
        return super.drain(this.requesterState, limit);
    }

    protected long manuallyPull() {
        if (!this.running.getOpaque()) {
            return requestAndPull();
        }
        return 0;
    }

    private long requestAndPull() {
        long localCache = super.getCacheCount();
        long batch = this.requesterState.batchSize;
        if(localCache >= batch) {
            return 0;
        }
        long remoteCache = super.getUpstreamCacheCount();
        long totalCache = localCache + remoteCache;

        long demand = this.requesterState.requestSize;
        if(totalCache < demand) {
            super.request(demand);
        }

        if(totalCache == 0 && super.getUpstreamCount() == 0) {
            this.requesterState.resetRequester();
            return 0;
        }

        long maxLocalCache = super.getMaxQueueCount();
        if(localCache >= maxLocalCache) {
            return 0;
        }

        long lowWaterMark = Math.min(maxLocalCache >> 2, batch << 2);
        lowWaterMark = Math.max(lowWaterMark, 1);

        if(localCache > lowWaterMark) {
            return 0;
        }

        long target = Math.min(maxLocalCache >> 1, batch << 3);
        target = Math.max(target, 1);

        long pull = target - localCache;

        long added = super.pull(pull);
        localCache += added;

        long now = System.nanoTime();
        this.requesterState.requestRecorder.record(now, this.requesterState.requestSize);
        this.requesterState.batchDiffRecorder.record(now, target - localCache);

        double avgDiff = this.requesterState.batchDiffRecorder.averageUnits();
        long avgRequest = Math.round(this.requesterState.requestRecorder.averageUnits());
        if(avgRequest == demand && localCache < lowWaterMark) {
            long step = Math.round(Math.sqrt(batch) * 2);
            step = Math.max(step, lowWaterMark - localCache);
            demand += step;
            this.requesterState.requestSize = Math.min(demand, Math.min(batch * this.requesterState.requestMultiplier, 65_536));
        } else if(avgRequest == demand && avgDiff < batch) {
            long step = Math.round(Math.sqrt(batch) * 2);
            step = Math.max(step, Math.round(batch - avgDiff));
            demand -= step;
            this.requesterState.requestSize = Math.max(demand, 4);
        }

        return added;
    }

    protected void updateRequester(long batchSize) {
        this.requesterState.batchSize = batchSize;
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

        protected final FlowRecorder batchDiffRecorder = new FlowRecorder();
        protected final FlowRecorder requestRecorder = new FlowRecorder();
        protected final long requestMultiplier = Math.max(SystemInfo.getCoreCount(), 2);
        private long batchSize = 2;
        private long requestSize = 4;

        public RequesterState(Consumer<AbstractFrame> consumer, boolean smt,
                long maxParkNs) {
            super(consumer);
            this.smt = smt;
            this.maxParkNs = maxParkNs;
        }

        protected void resetRequester() {
            this.batchDiffRecorder.reset();
            this.requestRecorder.reset();
            this.batchSize = 2;
            this.requestSize = 4;
        }
    }
}
