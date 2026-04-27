package euhedral.queues;

import static euhedral.queues.QueueUtils.LONG_PAD;
import static euhedral.queues.QueueUtils.POINTER_PAD_BYTES;

/// A bounded, padded, partitioned, array-based queue.
///
/// This class is not thread-safe for any method.
public class PartitionedArrayQueue<T> implements PartitionedQueue<T> {

    private final T[][] queue;
    private final int partitions;
    private final int chunkSize;
    private final int chunkMask;

    private final long[] heads;
    private final long[] tails;

    @SuppressWarnings("unchecked")
    public PartitionedArrayQueue(int partitions, int chunkSize) {
        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        this.partitions = partitions;
        this.chunkSize = chunkSize;
        this.chunkMask = chunkSize - 1;
        this.queue = (T[][]) new Object[(partitions + 1) * POINTER_PAD_BYTES + partitions][0];

        for (int i = 0; i < partitions; i++) {
            int pIdx = partitionIndex(i);
            this.queue[pIdx] = (T[]) new Object[chunkSize + POINTER_PAD_BYTES * 2];
        }
        this.heads = new long[(partitions + 1) * LONG_PAD + partitions];
        this.tails = new long[(partitions + 1) * LONG_PAD + partitions];
    }

    @Override
    public boolean offer(long randomSeed, T obj) {
        return false;
    }

    @Override
    public boolean offer(int partition, T obj) {
        boundsCheck(partition);
        if (obj == null) {
            throw new NullPointerException();
        }

        int pIdx = partitionIndex(partition);
        if (QueueUtils.unsignedDiff(heads[pIdx], tails[pIdx] + 1) > chunkSize) {
            return false;
        }

        int qIdx = queueIndex(tails[pIdx]++);
        queue[pIdx][qIdx] = obj;
        return true;
    }

    @Override
    public T peek(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        if (heads[pIdx] == tails[pIdx]) {
            return null;
        }
        return queue[pIdx][queueIndex(heads[pIdx])];
    }

    @Override
    public T poll(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        if (heads[pIdx] == tails[pIdx]) {
            return null;
        }
        return queue[pIdx][queueIndex(heads[pIdx]++)];
    }

    @Override
    public int drain(QueueConsumer<T> consumer, int limit) {
        int total = 0;
        for (int i = 0; i < partitions && total < limit; i++) {
            int count = drain(i, consumer, limit);
            limit -= count;
            total += count;
        }
        return total;
    }

    @Override
    public int drain(int partition, QueueConsumer<T> consumer, int limit) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        int total = 0;
        while(total < limit && heads[pIdx] < tails[pIdx]) {
            int qIdx = queueIndex(heads[pIdx]++);
            consumer.consume(queue[pIdx][qIdx]);
            total++;
        }
        return total;
    }

    private void boundsCheck(int partition) {
        if (partition < 0 || partition >= partitions) {
            throw new IndexOutOfBoundsException(
                    "Index " + partition + " out of bounds for length " + partitions);
        }
    }

    public int partitionIndex(int idx) {
        return (idx * LONG_PAD) + LONG_PAD + idx;
    }

    private int queueIndex(long idx) {
        return (int) (idx & chunkMask) + POINTER_PAD_BYTES;
    }
}
