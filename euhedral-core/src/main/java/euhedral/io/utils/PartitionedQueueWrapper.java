package euhedral.io.utils;

import euhedral.atomics.PaddedLongAdder;
import euhedral.io.frames.AbstractFrame;
import euhedral.queues.common.PartitionedQueue;
import org.jspecify.annotations.NonNull;

public class PartitionedQueueWrapper {

    protected final PartitionedQueue<AbstractFrame> queue;

    protected final PaddedLongAdder sizeBytes;

    public PartitionedQueueWrapper(PartitionedQueue<AbstractFrame> queue) {
        this.queue = queue;
        this.sizeBytes = new PaddedLongAdder(this.queue.partitions(), true, true);
    }

    public boolean offer(int partition, AbstractFrame frame) {
        if (this.queue.offer(partition, frame)) {
            this.sizeBytes.getAndAccumulate(partition, frame.getSizeBytes(), PartitionedQueueWrapper::addCap);
            return true;
        }
        return false;
    }

    public int drain(int partition, @NonNull DrainBuffer drainBuffer, int limit) {
        if (limit <= 0) {
            return 0;
        }

        drainBuffer.drainCount = 0;
        drainBuffer.drainedBytes = 0;
        int count = this.queue.drain(partition, drainBuffer::accept, limit);

        if (count > 0) {
            this.sizeBytes.getAndAccumulate(partition, -drainBuffer.drainedBytes,
                    PartitionedQueueWrapper::addCap);
        }
        return count;
    }

    public int partitions() {
        return this.queue.partitions();
    }

    public int maxPooledChunks() {
        return this.queue.maxPooledChunks();
    }

    public boolean isEmpty() {
        return this.queue.isEmpty();
    }

    public long getSizeBytes() {
        return this.sizeBytes.sum();
    }

    public long getSizeBytes(int partition) {
        return this.sizeBytes.getAcquire(partition);
    }

    public void clear() {
        this.queue.clear();
    }

    public void purge() {
        this.queue.purge();
    }

    private static long addCap(long num1, long num2) {
        long sum = num1 + num2;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
