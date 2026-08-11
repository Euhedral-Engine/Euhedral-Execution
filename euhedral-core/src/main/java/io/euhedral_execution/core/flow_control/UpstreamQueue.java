package io.euhedral_execution.core.flow_control;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeInterceptor;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.utils.MathFunctions;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import java.util.concurrent.atomic.AtomicLong;
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

    public UpstreamQueue(int core, MpscQueue<UpstreamHandle> upstreams, PaddedAtomicLong upstreamCount) {
        this.core = core;
        this.upstreams = upstreams;
        this.upstreamCount = upstreamCount;
    }

    public static UpstreamQueue get(
            MpscQueue<UpstreamHandle>[] upstreams, PaddedAtomicLong upstreamCount, AtomicLong counter) {
        UpstreamQueue queue = UP_QUEUE.get();
        if (queue == null) {
            int core = SystemInfo.getCpuInfo(ThreadTools.getCpu()).core();
            queue = new UpstreamQueue(core, upstreams[core], upstreamCount);
            UP_QUEUE.set(queue);
            counter.incrementAndGet();
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
                this.cachedUpCount--;
                continue;
            }

            if (!handle.acquireLock()) {
                // A transient acquisition failure must not permanently remove a live handle from
                // this owner-local queue. The cycle bound prevents immediate unbounded retries.
                this.upstreams.offer(handle);
                cycles++;
                continue;
            }

            try {
                long request = Math.min(limit, bucketSize);
                limit -= request;
                totalPull += drain(handle, consumer, stopCondition, request);
            } finally {
                handle.releaseLock();
            }
            cycles = 0;

            this.upstreams.offer(handle);
        }
        return totalPull;
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

        public boolean acquireLock() {
            return true;
        }

        public void releaseLock() {}
    }
}
