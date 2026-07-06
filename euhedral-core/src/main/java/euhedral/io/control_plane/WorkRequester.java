package euhedral.io.control_plane;

import euhedral.hardware_utils.SystemInfo;
import euhedral.io.config.CacheConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.FlowThread;
import euhedral.io.utils.QueueConsumer;
import org.jspecify.annotations.NonNull;

public abstract class WorkRequester extends ControlPlaneCache {

    private final QueueConsumer consumer;
    private final long safetyFactor;
    private final long pullMultiplier;

    public WorkRequester(@NonNull CacheConfig cacheConfig) {
        super(cacheConfig);

        if (super.getCache() == null) {
            this.consumer = null;
            this.safetyFactor = 0;
            this.pullMultiplier = 0;
        } else {
            this.consumer = new QueueConsumer(this::accept);

            int cores = SystemInfo.getSocketInfo(SystemInfo.getCoreInfo(cacheConfig.getCore()).socket()).getCoreSet()
                    .cardinality();
            int base = (cores * 3) >> 3;
            this.safetyFactor = Math.max(base >> 1, 2);
            this.pullMultiplier = this.safetyFactor << 1;
        }
    }

    protected abstract void accept(AbstractFrame frame);

    protected long drain(long limit) {
        return super.drain(this.consumer, limit);
    }

    protected void request(FlowThread.FlowContext context) {
        long remoteCapacity = super.getUpstreamCacheCapacity();
        long remoteCache = super.getUpstreamCacheCount();

        long highWaterMark = Math.round(remoteCapacity * super.capFactor.getAcquire()) >> 1;

        long demand = SystemInfo.SOCKET_COUNT * (highWaterMark - remoteCache);
        if (remoteCapacity > 0) {
            demand = Math.min(demand, Math.round(remoteCapacity * 0.05));
            demand = Math.max(demand, 1);
        }

        context.satisfiedRequest = 0;
        context.originalRequest = demand;
        if (remoteCache < highWaterMark || remoteCapacity == 0) {
            context.upstream.request(demand);
        }
    }

    protected long requestAndPull(FlowThread.FlowContext context, long batchSize) {
        long localCache = super.getCacheCount();

        long maxLocalCache = super.getMaxQueueCount();
        long lowWaterMark = Math.min(maxLocalCache >> 2, batchSize * this.safetyFactor);
        lowWaterMark = Math.max(lowWaterMark, 1);
        if (localCache >= lowWaterMark) {
            return 0;
        }

        long target = Math.min(maxLocalCache >> 1, batchSize * this.pullMultiplier);
        target = Math.max(target, 1);

        long pull = target - localCache;

        long remoteCache = super.getUpstreamCacheCount();
        long demand = batchSize * this.pullMultiplier;
        if ((remoteCache + localCache) < demand) {
            demand *= SystemInfo.SOCKET_COUNT;
            context.originalRequest = demand;
            context.upstream.request(demand);
        }

        context.originalPull = pull;
        context.satisfiedPull = super.pull(pull);
        return context.satisfiedPull;
    }
}
