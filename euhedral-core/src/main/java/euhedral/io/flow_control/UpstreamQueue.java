package euhedral.io.flow_control;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.utils.DrainBuffer;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import org.jctools.maps.NonBlockingHashMapLong;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;
import org.reactivestreams.Subscription;

public class UpstreamQueue {

    public static final ThreadLocal<UpstreamQueue> UP_QUEUE = new ThreadLocal<>();

    public static UpstreamQueue get(NonBlockingHashMapLong<UpstreamQueue> map,
            PaddedAtomicLong counter) {
        UpstreamQueue queue = UP_QUEUE.get();
        if (queue == null) {
            queue = new UpstreamQueue();
            UP_QUEUE.set(queue);
            map.put(Thread.currentThread().getId(), queue);
            counter.incrementAndGet();
        }
        return queue;
    }

    private final MpscUnboundedXaddArrayQueue<UpstreamHandle> upstreams = new MpscUnboundedXaddArrayQueue<>(
            512);
    private final UpstreamHandle[] drainBuffer = new UpstreamHandle[512];
    private final int[] pullIdx = new int[]{0};
    private final long[] pullBucket = new long[]{0, 0};
    private final AtomicLong upstreamCount = new AtomicLong(0);
    @Getter
    private long cachedUpCount = 0;

    public long getTrueUpstreamCount() {
        return (this.cachedUpCount = this.upstreamCount.getAcquire());
    }

    public void pull(long demand) {
        pull(null, demand);
    }

    public void pull(DrainBuffer buffer, long demand) {
        this.cachedUpCount = this.upstreamCount.getAcquire();
        this.pullIdx[0] = 0;

        if (demand == 0 || this.cachedUpCount == 0) {
            return;
        }

        int count;
        long removed = 0;
        calculatePullBuckets(demand);

        boolean workDone = true;
        while (workDone && (count = fillUpstreamBuffer()) > 0) {
            workDone = false;
            for (int i = 0; i < count; i++) {
                UpstreamHandle handle = this.drainBuffer[i];
                if (!handle.isComplete()) {
                    if (demand > 0) {
                        long requestAmount = Math.min(demand, this.pullBucket[1]);
                        demand -= requestAmount;
                        workDone = true;
                        drain(handle, buffer, requestAmount);
                    }
                    while (!this.upstreams.relaxedOffer(handle)) {
                        Thread.onSpinWait();
                    }
                } else {
                    this.drainBuffer[i] = null;
                    removed++;
                }
            }
        }
        this.cachedUpCount = this.upstreamCount.addAndGet(-removed);
    }

    protected static void drain(UpstreamHandle handle, DrainBuffer buffer,
            long demand) {
        if (buffer != null) {
            long limit = buffer.buffer.capacity() < 0 ? demand
                    : Math.min(buffer.buffer.capacity(), demand);
            handle.pull(buffer, limit);
        }
        handle.request(demand);
    }

    private void calculatePullBuckets(long demand) {
        int start = 0;
        int end = Math.max((int) this.upstreamCount.getAcquire(), 1);
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

    private int fillUpstreamBuffer() {
        if (this.pullBucket[0] <= 0) {
            return 0;
        }

        this.pullIdx[0] = 0;
        return this.upstreams.drain(
                sub -> this.drainBuffer[this.pullIdx[0]++] = sub,
                Math.min((int) this.pullBucket[0], this.drainBuffer.length));
    }

    public void addUpstream(UpstreamHandle upstream) {
        while (!this.upstreams.relaxedOffer(upstream)) {
            Thread.onSpinWait();
        }
        this.upstreamCount.incrementAndGet();
    }

    public static abstract class UpstreamHandle implements Subscription {

        public abstract void pull(DrainBuffer buffer, long demand);

        public abstract boolean isComplete();
    }
}
