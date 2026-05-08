package euhedral.io.flow_control;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.utils.DrainBuffer;
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
    private final PaddedAtomicLong upstreamCount = new PaddedAtomicLong(0);
    @Getter
    private long cachedUpCount = 0;

    public long getTrueUpstreamCount() {
        return (cachedUpCount = upstreamCount.get());
    }

    public void pull(long demand) {
        pull(null, demand);
    }

    public void pull(DrainBuffer buffer, long demand) {
        cachedUpCount = upstreamCount.get();
        pullIdx[0] = 0;

        if (demand == 0 || cachedUpCount == 0) {
            return;
        }

        int count;
        long removed = 0;
        calculatePullBuckets(demand);

        boolean workDone = true;
        while (workDone && (count = fillUpstreamBuffer()) > 0) {
            workDone = false;
            for (int i = 0; i < count; i++) {
                UpstreamHandle handle = drainBuffer[i];
                if (!handle.isComplete()) {
                    if (demand > 0) {
                        long requestAmount = Math.min(demand, pullBucket[1]);
                        demand -= requestAmount;
                        workDone = true;
                        drain(handle, buffer, requestAmount);
                    }
                    while (!upstreams.relaxedOffer(handle)) {
                        Thread.onSpinWait();
                    }
                } else {
                    drainBuffer[i] = null;
                    removed++;
                }
            }
        }
        cachedUpCount = upstreamCount.addAndGet(-removed);
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
        int end = Math.max((int) upstreamCount.get(), 1);
        pullBucket[0] = end - start;
        pullBucket[1] = demand;

        if (demand <= 1024 || end <= 1) {
            pullBucket[0] = 1;
            return;
        }

        if (demand > 32L * end) {
            pullBucket[1] = demand / end;
            return;
        }

        int mid = (end - start) / 2;

        while (mid != 0) {
            pullBucket[1] = demand / mid;
            if (pullBucket[1] < 32) {
                end = mid;
                mid = (end - start) / 2;
            } else {
                mid = (end - mid) / 2;
            }
        }
        pullBucket[0] = end - start;
        pullBucket[0] = Math.max(pullBucket[0], 1);
    }

    private int fillUpstreamBuffer() {
        if (pullBucket[0] <= 0) {
            return 0;
        }

        pullIdx[0] = 0;
        return upstreams.drain(
                sub -> drainBuffer[pullIdx[0]++] = sub,
                Math.min((int) pullBucket[0], drainBuffer.length));
    }

    public void addUpstream(UpstreamHandle upstream) {
        while (!upstreams.relaxedOffer(upstream)) {
            Thread.onSpinWait();
        }
        upstreamCount.incrementAndGet();
    }

    public static abstract class UpstreamHandle implements Subscription {

        public abstract void pull(DrainBuffer buffer, long demand);

        public abstract boolean isComplete();
    }
}
