package io.euhedral_execution.core.flow_control;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeInterceptor;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.core.utils.MathFunctions;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import java.util.function.Consumer;
import java.util.function.Function;

/// ## The upstream aggregation and scheduling layer
///
/// `UpstreamQueue` is a thread-local coordination point for upstream sources feeding a
/// [LatticeEdge] graph.
///
/// Each thread owns a single queue instance which aggregates upstream handles and participates in
/// global demand distribution.
///
/// **Core behavior:**
/// - Collect upstream handles per thread
/// - Track active upstream count
/// - Distribute pull demand across all active handles
///
///
/// This avoids global contention by keeping scheduling localized per thread.
public class UpstreamQueue {

    public static final ThreadLocal<UpstreamQueue> UP_QUEUE = new ThreadLocal<>();
    public final int core;
    private final MpscQueue<UpstreamHandle> upstreams;
    private final PaddedAtomicLong upstreamCount;
    long cachedUpCount = 0L;
    long nonproductiveCount = 0L;

    public UpstreamQueue(int core, MpscQueue<UpstreamHandle> upstreams, PaddedAtomicLong upstreamCount) {
        this.core = core;
        this.upstreams = upstreams;
        this.upstreamCount = upstreamCount;
    }

    /// Returns the caller's thread-local queue without changing active-worker registration state.
    public static UpstreamQueue get(MpscQueue<UpstreamHandle>[] upstreams, PaddedAtomicLong upstreamCount) {
        UpstreamQueue queue = UP_QUEUE.get();
        if (queue == null) {
            int core = SystemInfo.getCpuInfo(ThreadTools.getCpu()).core();
            queue = new UpstreamQueue(core, upstreams[core], upstreamCount);
            UP_QUEUE.set(queue);
        }
        return queue;
    }

    protected static long drain(
            UpstreamHandle handle,
            Consumer<AbstractFrame> consumer,
            Function<AbstractFrame, Boolean> stopCondition,
            long demand) {
        if (consumer != null) {
            return handle.pull(consumer, stopCondition, demand);
        }
        handle.request(demand);
        return 0;
    }

    public boolean inSync() {
        return getTrueUpstreamCount() <= this.upstreams.sizeLong();
    }

    public long getCachedUpCount() {
        if (this.cachedUpCount == 0L) {
            return getTrueUpstreamCount();
        }
        return this.cachedUpCount;
    }

    public long getTrueUpstreamCount() {
        this.cachedUpCount = this.upstreamCount.getAcquire();
        return this.cachedUpCount;
    }

    /// Returns live handles minus handles this worker last observed as nonproductive.
    ///
    /// New handles are optimistic until this worker services them. Completed handles are reconciled
    /// from this owner-local queue; no productivity state is published between workers.
    public long getProductiveHandleCount() {
        getTrueUpstreamCount();
        removeCompletedHandles();
        return this.cachedUpCount - Math.min(this.cachedUpCount, this.nonproductiveCount);
    }

    public void request(long demand) {
        pull(null, null, demand);
    }

    /// Pulls work without requesting from the [UpstreamHandles][UpstreamHandle]. If the consumer is
    /// `null`, it will **request** the work.
    public long pull(Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
        getTrueUpstreamCount();

        if (demand <= 0 || this.cachedUpCount == 0) {
            return 0;
        }

        FlowThread.FlowContext context = FlowThread.getContext();
        long totalPull = 0;
        long bucketSize = calculatePullBuckets(demand);

        long limit = demand;
        int cycles = 0;
        // Cycle through the queue and pull round-robin style.
        while (cycles < this.cachedUpCount && limit > 0) {
            UpstreamHandle handle = this.upstreams.poll();

            if (handle == null) {
                cycles++;
                continue;
            }
            if (handle.isComplete()) {
                observeRemoval(handle);
                continue;
            }

            boolean wasProductive = handle.isProductive();
            if (!handle.acquireLock()) {
                this.upstreams.offer(handle);
                cycles++;
                continue;
            }

            try {
                long requestBefore = context == null || consumer != null ? 0L : context.satisfiedRequest;
                long request = Math.min(limit, bucketSize);
                limit -= request;

                long drainCount = drain(handle, consumer, stopCondition, request);
                totalPull += drainCount;
                if (context != null) {
                    context.satisfiedPull += drainCount;
                }

                if (consumer == null) {
                    if (context != null && context.satisfiedRequest != requestBefore) {
                        handle.setProductivity(true);
                    } else if (!handle.isProductive()) {
                        // Request has no empty-source result. Without a synchronous push, it
                        // supplies no new evidence and retains the worker's prior observation.
                        handle.setProductivity(wasProductive);
                    }
                }

                boolean produced = handle.isProductive();
                if (!wasProductive && produced) {
                    if (this.nonproductiveCount > 0L) {
                        this.nonproductiveCount--;
                    }
                } else if (wasProductive && !produced) {
                    this.nonproductiveCount++;
                }
            } finally {
                handle.releaseLock();
            }
            cycles = 0;

            this.upstreams.offer(handle);
        }
        return totalPull;
    }

    /// Removes completed queue entries when lifecycle changes occur without another pull.
    private void removeCompletedHandles() {
        long queued = this.upstreams.sizeLong();
        long surplus = queued - this.cachedUpCount;
        while (queued > 0L && surplus > 0L) {
            UpstreamHandle handle = this.upstreams.poll();
            if (handle == null) {
                return;
            }
            queued--;
            if (handle.isComplete()) {
                observeRemoval(handle);
                surplus--;
            } else {
                this.upstreams.offer(handle);
            }
        }
    }

    private void observeRemoval(UpstreamHandle handle) {
        if (!handle.isProductive() && this.nonproductiveCount > 0L) {
            this.nonproductiveCount--;
        }
    }

    /// Performs a binary search to calculate even buckets of 32 items or more per [UpstreamHandle]
    /// ```
    /// pullBucket[0] = Number of buckets
    /// pullBucket[1] = Size of each bucket
    /// ```
    protected long calculatePullBuckets(long demand) {
        if (demand <= 2048 || this.cachedUpCount < 2) {
            return demand;
        }

        int buckets = (int) MathFunctions.clampLong(demand / 2048, 1L, this.cachedUpCount);
        buckets = Math.max(buckets, 1);

        return (demand + buckets - 1) / buckets;
    }

    /// A wrapper for an upstream source.
    public abstract static class UpstreamHandle implements LatticeInterceptor {

        public abstract long getId();

        public void addUpstream(LatticeSource upstream) {
            upstream.complete();
        }

        public void addDownstream(LatticeReceiver terminal) {
            terminal.onError(new IllegalStateException("Not supported"));
        }

        public boolean isProductive() {
            return true;
        }

        /// Sets this worker's plain observation after classifying one acquired service.
        public void setProductivity(boolean productive) {}

        public boolean acquireLock() {
            return true;
        }

        public void releaseLock() {}
    }
}
