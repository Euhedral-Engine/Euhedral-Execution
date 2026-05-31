package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeInterceptor;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import euhedral.queues.PartitionedUnboundedMpscArrayQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.Getter;
import org.jctools.maps.NonBlockingHashMapLong;

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
    protected static final VarHandle UP_COUNT;

    static {
        try {
            UP_COUNT = MethodHandles.lookup()
                    .findVarHandle(UpstreamQueue.class, "upstreamCount", long.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static UpstreamQueue get(NonBlockingHashMapLong<UpstreamQueue> map, AtomicLong counter) {
        UpstreamQueue queue = UP_QUEUE.get();
        if (queue == null) {
            queue = new UpstreamQueue();
            UP_QUEUE.set(queue);
            map.put(Thread.currentThread().getId(), queue);
            counter.incrementAndGet();
        }
        return queue;
    }

    protected static void drain(UpstreamHandle handle, Consumer<AbstractFrame> consumer,
            long demand) {
        if (consumer != null) {
            handle.pull(consumer, demand);
            return;
        }
        handle.request(demand);
    }

    final long[] pullBucket = new long[]{0L, 0L};
    private final PartitionedUnboundedMpscArrayQueue<UpstreamHandle> upstreams =
            new PartitionedUnboundedMpscArrayQueue<>(1, 512, 0);
    private final UpstreamHandle[] drainBuffer = new UpstreamHandle[512];
    private final int[] pullIdx = new int[]{0};
    @Getter
    long cachedUpCount = 0L;
    private long upstreamCount = 0L;

    public long getTrueUpstreamCount() {
        this.cachedUpCount = (long) UP_COUNT.getOpaque(this);
        return this.cachedUpCount;
    }

    public void addUpstream(UpstreamHandle upstream) {
        while (!this.upstreams.offer(upstream)) {
            Thread.onSpinWait();
        }
        UP_COUNT.getAndAdd(this, 1);
    }

    public void request(long demand) {
        pull(null, demand);
    }

    /// Pulls work without requesting from the [UpstreamHandles][UpstreamHandle]. If the consumer is
    /// `null`, it will **request** the work.
    public void pull(Consumer<AbstractFrame> consumer, long demand) {
        getTrueUpstreamCount();
        this.pullIdx[0] = 0;

        if (demand == 0 || this.cachedUpCount == 0) {
            return;
        }

        int count;
        long removed = 0;
        calculatePullBuckets(demand);

        boolean workDone = true;
        // Cycle through the queue and pull round-robin style.
        while (workDone && (count = fillUpstreamBuffer()) > 0) {
            workDone = false;
            for (int i = 0; i < count; i++) {
                UpstreamHandle handle = this.drainBuffer[i];
                if (!handle.isComplete()) {
                    if (demand > 0) {
                        long requestAmount = Math.min(demand, this.pullBucket[1]);
                        demand -= requestAmount;
                        workDone = true;
                        drain(handle, consumer, requestAmount);
                    }
                    while (!this.upstreams.offer(handle)) {
                        Thread.onSpinWait();
                    }
                } else {
                    this.drainBuffer[i] = null;
                    removed++;
                }
            }
        }
        this.cachedUpCount = (long) UP_COUNT.getAndAdd(this, -removed) - removed;
    }

    /// Performs a binary search to calculate even buckets of 32 items or more per [UpstreamHandle]
    /// ```
    /// pullBucket[0] = Number of buckets
    /// pullBucket[1] = Size of each bucket
    /// ```
    protected void calculatePullBuckets(long demand) {
        int start = 0;
        int end = (int) this.cachedUpCount;
        end = Math.max(end, 1);
        this.pullBucket[0] = end - start;
        this.pullBucket[1] = demand;

        if (demand <= 1024 || end <= 1) {
            this.pullBucket[0] = 1;
            return;
        }

        if (demand > 32L * end) {
            this.pullBucket[1] = demand / end;
            return;
        }

        int mid = (end - start) / 2;

        while (mid != 0) {
            this.pullBucket[1] = demand / mid;
            if (this.pullBucket[1] < 32) {
                end = mid;
                mid = (end - start) / 2;
            } else {
                mid = (end - mid) / 2;
            }
        }
        this.pullBucket[0] = end - start;
        this.pullBucket[0] = Math.max(this.pullBucket[0], 1);
    }

    /// Transfers [UpstreamHandle] objects into the class's drainBuffer.
    protected int fillUpstreamBuffer() {
        if (this.pullBucket[0] <= 0) {
            return 0;
        }

        this.pullIdx[0] = 0;
        return (int) this.upstreams.drain(sub -> this.drainBuffer[this.pullIdx[0]++] = sub,
                Math.min((int) this.pullBucket[0], this.drainBuffer.length));
    }

    /// A wrapper for an upstream source.
    public static abstract class UpstreamHandle implements LatticeInterceptor {

        public abstract void pull(Consumer<AbstractFrame> consumer, long demand);

        public abstract boolean isComplete();

        public void addUpstream(LatticeSource upstream) {
            upstream.complete();
        }

        public void addDownstream(LatticeReceiver terminal) {
            terminal.onError(new IllegalStateException("Not supported"));
        }
    }
}
