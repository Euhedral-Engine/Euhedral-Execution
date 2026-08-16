package io.euhedral_execution.core.flow_control;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeInterceptor;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.utils.AverageFlow;
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

    public static final long ACQUIRE_CONTENTION_SCALE = 1_000_000L;
    private static final long MAX_SCALED_FAILURES = Long.MAX_VALUE / ACQUIRE_CONTENTION_SCALE;

    public static final ThreadLocal<UpstreamQueue> UP_QUEUE = new ThreadLocal<>();
    public final int core;
    private final MpscQueue<UpstreamHandle> upstreams;
    private final PaddedAtomicLong upstreamCount;
    private final AverageFlow acquireContention = new AverageFlow();
    long cachedUpCount = 0L;

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

    /// Returns whether this worker has completed an eligible acquisition cycle since reset.
    public boolean hasAcquireContention() {
        return this.acquireContention.initialized();
    }

    /// Returns this worker's fixed-point acquisition EWMA; validity is reported separately.
    public long getContention() {
        if (!this.acquireContention.initialized()) {
            return 0L;
        }
        return this.acquireContention.value();
    }

    /// Returns the fixed-point EWMA or `-1` when no eligible acquisition cycle has been observed.
    public long getAcquireContentionOrUninitialized() {
        return this.acquireContention.initialized() ? this.acquireContention.value() : -1L;
    }

    /// Normalizes the worker-local fixed-point value only for diagnostics and external reporting.
    public double getNormalizedAcquireContention() {
        return this.acquireContention.initialized()
                ? this.acquireContention.value() / (double) ACQUIRE_CONTENTION_SCALE
                : Double.NaN;
    }

    /// Resets acquisition history under the existing worker-owner lifecycle handoff.
    public void resetAcquireContention() {
        this.acquireContention.reset();
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
        long attempts = 0L;
        long failedAcquires = 0L;

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
                continue;
            }

            attempts++;
            if (!handle.acquireLock()) {
                failedAcquires++;
                this.upstreams.offer(handle);
                cycles++;
                continue;
            }

            try {
                long request = Math.min(limit, bucketSize);
                limit -= request;

                long drainCount = drain(handle, consumer, stopCondition, request);
                totalPull += drainCount;
                if (context != null) {
                    context.satisfiedPull += drainCount;
                }
            } finally {
                handle.releaseLock();
            }
            cycles = 0;

            this.upstreams.offer(handle);
        }
        if (attempts > 0L) {
            this.acquireContention.record(scaleAcquireContentionUnchecked(failedAcquires, attempts));
        }
        return totalPull;
    }

    /// Scales a valid failed/attempt count for deterministic boundary tests and diagnostics.
    static long scaleAcquireContention(long failedAcquires, long attempts) {
        if (attempts <= 0L || failedAcquires < 0L || failedAcquires > attempts) {
            throw new IllegalArgumentException("Acquisition counts require 0 <= failures <= positive attempts");
        }
        return scaleAcquireContentionUnchecked(failedAcquires, attempts);
    }

    /// Keeps the scheduler-domain multiply/divide fast while handling wider public queue inputs.
    private static long scaleAcquireContentionUnchecked(long failedAcquires, long attempts) {
        if (failedAcquires <= MAX_SCALED_FAILURES) {
            return failedAcquires * ACQUIRE_CONTENTION_SCALE / attempts;
        }
        return scaleAcquireContentionLarge(failedAcquires, attempts);
    }

    /// Produces six exact decimal fraction digits without forming an overflowing product.
    private static long scaleAcquireContentionLarge(long failedAcquires, long attempts) {
        if (failedAcquires == attempts) {
            return ACQUIRE_CONTENTION_SCALE;
        }
        long remainder = failedAcquires;
        long scaled = 0L;
        for (int place = 0; place < 6; place++) {
            long nextRemainder = 0L;
            long digit = 0L;
            for (int add = 0; add < 10; add++) {
                if (nextRemainder >= attempts - remainder) {
                    nextRemainder -= attempts - remainder;
                    digit++;
                } else {
                    nextRemainder += remainder;
                }
            }
            scaled = scaled * 10L + digit;
            remainder = nextRemainder;
        }
        return scaled;
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
                surplus--;
            } else {
                this.upstreams.offer(handle);
            }
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

        public boolean acquireLock() {
            return true;
        }

        public void releaseLock() {}
    }
}
