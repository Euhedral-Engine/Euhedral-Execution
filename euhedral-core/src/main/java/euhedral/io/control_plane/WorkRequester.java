package euhedral.io.control_plane;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.SystemInfo;
import euhedral.io.config.CacheConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.FlowThread;
import euhedral.io.utils.QueueConsumer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

public abstract class WorkRequester extends ControlPlaneCache {

    private final RequesterState requesterState;

    private final PinnedThreadExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    protected volatile Thread cycleThread;

    public WorkRequester(@NonNull CacheConfig cacheConfig, long maxParkNs, PinnedThreadExecutor executor) {
        super(cacheConfig);

        if(super.getCache() == null) {
            this.requesterState = null;
            this.executor = null;
        } else {
            this.requesterState = new RequesterState(this::accept, executor != null, maxParkNs,
                    cacheConfig.getCore());
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

    }

    protected long drain(long limit) {
        return super.drain(this.requesterState, limit);
    }

    protected void request() {
        long remoteCapacity = super.getUpstreamCacheCapacity();
        long remoteCache = super.getUpstreamCacheCount();

        long highWaterMark = Math.round(remoteCapacity * super.capFactor.getAcquire()) >> 1;

        long demand = SystemInfo.CORE_COUNT * 2_048L;
        if(remoteCapacity > 0) {
            demand = Math.max(demand, Math.round(remoteCapacity * 0.05));
            demand = Math.min(demand, highWaterMark - remoteCache);
            demand = Math.max(demand, 1);
        }

        FlowThread.FlowContext context = FlowThread.getContext();
        Objects.requireNonNull(context);
        context.satisfiedRequest = 0;
        context.originalRequest = demand;
        if(remoteCache < highWaterMark || remoteCapacity == 0) {
            super.request(demand);
        }
    }

    protected long requestAndPull(long batchSize) {
        FlowThread.FlowContext context = FlowThread.getContext();
        Objects.requireNonNull(context);
        context.clearCounters();

        long localCache = super.getCacheCount();

        long maxLocalCache = super.getMaxQueueCount();
        long lowWaterMark = Math.min(maxLocalCache >> 2, batchSize * this.requesterState.safetyFactor);
        lowWaterMark = Math.max(lowWaterMark, 1);
        if(localCache >= lowWaterMark) {
            return 0;
        }

        long target = Math.min(maxLocalCache >> 1, batchSize * this.requesterState.pullMultiplier);
        target = Math.max(target, 1);

        long pull = target - localCache;

        long remoteCache = super.getUpstreamCacheCount();
        long demand = batchSize * this.requesterState.pullMultiplier;
        if((remoteCache + localCache) < demand) {
            context.originalRequest = demand;
            super.request(demand);
        }


        context.originalPull = pull;
        context.satisfiedPull = super.pull(pull);
        return context.satisfiedPull;
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

    private static class RequesterState extends QueueConsumer {

        public final long maxParkNs;
        public final boolean smt;

        protected final long safetyFactor;
        protected final long pullMultiplier;

        public RequesterState(Consumer<AbstractFrame> consumer, boolean smt,
                long maxParkNs, int core) {
            super(consumer);
            this.smt = smt;
            this.maxParkNs = maxParkNs;

            int cores = SystemInfo.getSocketInfo(SystemInfo.getCoreInfo(core).socket()).getCoreSet().cardinality();
            int base = (cores * 3) >> 3;
            this.safetyFactor = Math.max(base >> 1, 2);
            this.pullMultiplier = this.safetyFactor << 1;
        }
    }
}
