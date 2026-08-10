package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.core.config.CacheConfig;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.hardware_utils.SystemInfo;
import org.jspecify.annotations.NonNull;

public abstract class WorkRequester extends ControlPlaneCache {

    private final long safetyFactor;
    private final long pullMultiplier;

    protected WorkRequester(@NonNull CacheConfig cacheConfig) {
        super(cacheConfig);

        if (super.getLocalCache() == null) {
            this.safetyFactor = 0;
            this.pullMultiplier = 0;
        } else {
            int cores = SystemInfo.getSocketInfo(
                            SystemInfo.getCoreInfo(cacheConfig.getCore()).socket())
                    .getCoreSet()
                    .cardinality();
            this.pullMultiplier = Math.max((cores * 3L) >> 3, 2); // 37.5% of the core count
            this.safetyFactor = Math.max(this.pullMultiplier >> 1, 2);
        }
    }

    protected abstract void accept(AbstractFrame frame);

    protected long drain(long limit) {
        return super.drain(this::accept, limit);
    }

    protected void request(FlowThread.FlowContext context) {
        long remoteCapacity = super.getUpstreamCacheCapacity();
        long remoteCache = super.getUpstreamCacheCount();

        long highWaterMark = Math.round(remoteCapacity * super.getCapFactor()) >> 1;

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

    protected void requestAndPull(FlowThread.FlowContext context, long batchSize) {
        long localCache = super.getLocalCacheCount();

        long maxLocalCache = super.getMaxLocalCacheCount();
        long lowWaterMark = Math.min(maxLocalCache >> 2, batchSize * this.safetyFactor);
        lowWaterMark = Math.max(lowWaterMark, 1);
        if (localCache >= lowWaterMark) {
            return;
        }

        long target = Math.min(maxLocalCache >> 1, batchSize * this.pullMultiplier);
        target = Math.max(target, 1);

        long pull = target - localCache;
        context.originalPull = pull;

        long remoteCache = super.getUpstreamCacheCount();
        if (remoteCache > 0) {
            context.satisfiedPull = super.pull(pull);
        }

        long demand = batchSize * this.pullMultiplier;

        if ((remoteCache + localCache) < demand) {
            demand *= SystemInfo.SOCKET_COUNT;
            context.originalRequest = demand;
            context.upstream.request(demand);
        }
    }

    protected void request(FlowThread.FlowContext context, long remoteCache, long localCache, long batchSize) {
        long demand = batchSize * this.pullMultiplier;

        if ((remoteCache + localCache) < demand) {
            demand *= SystemInfo.SOCKET_COUNT;
            context.originalRequest = demand;
            context.upstream.request(demand);
        }
    }
}
