package euhedral.io.control_plane;

import euhedral.hardware_utils.SystemInfo;
import euhedral.io.config.CacheConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.FlowThread;
import euhedral.io.utils.QueueConsumer;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

public abstract class WorkRequester extends ControlPlaneCache {

    private final RequesterState requesterState;

    public WorkRequester(@NonNull CacheConfig cacheConfig, long maxParkNs) {
        super(cacheConfig);

        if (super.getCache() == null) {
            this.requesterState = null;
        } else {
            this.requesterState = new RequesterState(this::accept, maxParkNs,
                    cacheConfig.getCore());
        }
    }

    protected abstract void accept(AbstractFrame frame);

    protected long drain(long limit) {
        return super.drain(this.requesterState, limit);
    }

    protected void request() {
        long remoteCapacity = super.getUpstreamCacheCapacity();
        long remoteCache = super.getUpstreamCacheCount();

        long highWaterMark = Math.round(remoteCapacity * super.capFactor.getAcquire()) >> 1;

        long demand = SystemInfo.SOCKET_COUNT * (highWaterMark - remoteCache);
        if (remoteCapacity > 0) {
            demand = Math.min(demand, Math.round(remoteCapacity * 0.05));
            demand = Math.max(demand, 1);
        }

        FlowThread.FlowContext context = FlowThread.getContext();
        Objects.requireNonNull(context);
        context.satisfiedRequest = 0;
        context.originalRequest = demand;
        if (remoteCache < highWaterMark || remoteCapacity == 0) {
            super.request(demand);
        }
    }

    protected long requestAndPull(long batchSize) {
        FlowThread.FlowContext context = FlowThread.getContext();
        Objects.requireNonNull(context);
        context.clearCounters();

        long localCache = super.getCacheCount();

        long maxLocalCache = super.getMaxQueueCount();
        long lowWaterMark = Math.min(maxLocalCache >> 2,
                batchSize * this.requesterState.safetyFactor);
        lowWaterMark = Math.max(lowWaterMark, 1);
        if (localCache >= lowWaterMark) {
            return 0;
        }

        long target = Math.min(maxLocalCache >> 1, batchSize * this.requesterState.pullMultiplier);
        target = Math.max(target, 1);

        long pull = target - localCache;

        long remoteCache = super.getUpstreamCacheCount();
        long demand = batchSize * this.requesterState.pullMultiplier;
        if ((remoteCache + localCache) < demand) {
            demand *= SystemInfo.SOCKET_COUNT;
            context.originalRequest = demand;
            super.request(demand);
        }

        context.originalPull = pull;
        context.satisfiedPull = super.pull(pull);
        return context.satisfiedPull;
    }

    private static class RequesterState extends QueueConsumer {

        public final long maxParkNs;

        protected final long safetyFactor;
        protected final long pullMultiplier;

        public RequesterState(Consumer<AbstractFrame> consumer, long maxParkNs, int core) {
            super(consumer);
            this.maxParkNs = maxParkNs;

            int cores = SystemInfo.getSocketInfo(SystemInfo.getCoreInfo(core).socket()).getCoreSet()
                    .cardinality();
            int base = (cores * 3) >> 3;
            this.safetyFactor = Math.max(base >> 1, 2);
            this.pullMultiplier = this.safetyFactor << 1;
        }
    }
}
