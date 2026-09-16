package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.core.config.CacheConfig;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.hardware_utils.SystemInfo;
import org.jspecify.annotations.NonNull;

public abstract class WorkRequester extends ControlPlaneCache {

    private final long safetyFactor;
    private final long pullMultiplier;

    protected WorkRequester(@NonNull CacheConfig cacheConfig, int cpu, boolean smtEnabled) {
        super(cacheConfig, cpu, smtEnabled);

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

    protected void request(FlowThread.FlowContext context, long batchSize) {
        context.clearCounters();
        long maxLocalCache = super.getMaxLocalCacheCount();

        long demand = batchSize * this.pullMultiplier;
        demand = Math.min(demand, maxLocalCache);

        context.originalRequest = demand;
        demand *= SystemInfo.SOCKET_COUNT;
        context.upstream.request(demand);
    }
}
