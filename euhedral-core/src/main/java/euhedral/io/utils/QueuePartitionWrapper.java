package euhedral.io.utils;

import euhedral.io.frames.AbstractFrame;
import euhedral.queues.common.PartitionedQueue;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

public class QueuePartitionWrapper {

    @Getter
    protected final PartitionedQueue<AbstractFrame> queue;

    protected final PaddedLongAdder sizeBytes;

    public QueuePartitionWrapper(PartitionedQueue<AbstractFrame> queue) {
        this.queue = queue;
        this.sizeBytes = new PaddedLongAdder(this.queue.partitions(), true, true);
    }

    public boolean offer(int partition, AbstractFrame frame) {
        if (this.queue.offer(partition, frame)) {
            this.sizeBytes.getAndAccumulate(partition, frame.getSizeBytes(), QueuePartitionWrapper::addCap);
            return true;
        }
        return false;
    }

    public long drain(int partition, @NonNull DrainBuffer drainBuffer, int limit) {
        if (limit <= 0) {
            return 0;
        }

        drainBuffer.drainCount = 0;
        drainBuffer.drainedBytes = 0;
        long count = this.queue.drain(partition, drainBuffer::accept, limit);

        if (count > 0) {
            this.sizeBytes.getAndAccumulate(partition, -drainBuffer.drainedBytes,
                    QueuePartitionWrapper::addCap);
        }
        return count;
    }

    public int partitions() {
        return this.queue.partitions();
    }

    public boolean isEmpty() {
        return this.queue.isEmpty();
    }

    public boolean isEmpty(int partition) {
        return this.queue.isEmpty(partition);
    }

    public long getSizeBytes() {
        return this.sizeBytes.sum();
    }

    public long getSizeBytes(int partition) {
        return this.sizeBytes.getAcquire(partition);
    }

    public void purge() {
        this.queue.purge();
    }

    private static long addCap(long num1, long num2) {
        long sum = num1 + num2;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
