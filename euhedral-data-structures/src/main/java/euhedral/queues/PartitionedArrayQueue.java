package euhedral.queues;

import static euhedral.queues.common.QueueUtils.LONG_PAD;
import static euhedral.queues.common.QueueUtils.POINTER_PAD_BYTES;

import euhedral.queues.common.PartitionedQueue;
import euhedral.queues.common.QueueUtils;
import java.util.Arrays;
import lombok.Getter;

/// A bounded, padded, partitioned, array-based queue.
///
/// This class is not thread-safe for any method. This is meant to be used by a single thread and
/// there are no visibility guarantees between 2 different threads.
public class PartitionedArrayQueue<T> implements PartitionedQueue<T> {

    protected final T[][] queue;

    @Getter
    protected final int partitions;
    @Getter
    protected final int chunkSize;
    protected final int chunkMask;

    protected final long[] heads;
    protected final long[] tails;

    protected final boolean unbounded;

    @Getter
    long capacity;

    @SuppressWarnings("unchecked")
    public PartitionedArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        this.partitions = partitions;
        this.chunkSize = chunkSize;
        this.chunkMask = chunkSize - 1;
        this.queue = (T[][]) new Object[(partitions + 1) * POINTER_PAD_BYTES + partitions][0];

        for (int i = 0; i < partitions; i++) {
            this.queue[queueIndex(i)] = (T[]) new Object[chunkSize + POINTER_PAD_BYTES * 2];
        }
        this.heads = new long[(partitions + 1) * LONG_PAD + partitions];
        this.tails = new long[(partitions + 1) * LONG_PAD + partitions];
        this.unbounded = unbounded;
        this.capacity = (long) chunkSize * partitions;
    }

    /// Offers an item to a random partition.
    ///
    /// @param randomSeed Random number to assign a partition from
    /// @param obj        Item to add
    @Override
    public boolean offer(long randomSeed, T obj) {
        int partition = (int) QueueUtils.unsignedMultiplyHigh(randomSeed, this.partitions);
        return offer(partition, obj);
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

        int chunkIdx = chunkIndex(tails[pIdx]++);
        queue[queueIndex(partition)][chunkIdx] = obj;
        return true;
    }

    @Override
    public T peek(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        if (heads[pIdx] == tails[pIdx]) {
            return null;
        }
        return queue[queueIndex(partition)][chunkIndex(heads[pIdx])];
    }

    @Override
    public T poll(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        if (heads[pIdx] == tails[pIdx]) {
            return null;
        }
        return queue[queueIndex(partition)][chunkIndex(heads[pIdx]++)];
    }

    /// Drains from all partitions sequentially starting from 0.
    ///
    /// @param consumer Consumer to drain items into
    /// @param limit    Max number of items to take
    @Override
    public int drain(QueueConsumer<T> consumer, int limit) {
        if (consumer == null || limit <= 0) {
            return 0;
        }

        int total = 0;
        for (int i = 0; i < partitions && total < limit; i++) {
            total += drain(i, consumer, limit - total);
        }
        return total;
    }

    @Override
    public int drain(int partition, QueueConsumer<T> consumer, int limit) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        int total = 0;
        int qIdx = queueIndex(partition);
        while (total < limit && heads[pIdx] < tails[pIdx]) {
            int chunkIdx = chunkIndex(heads[pIdx]++);
            consumer.consume(queue[qIdx][chunkIdx]);
            total++;
        }
        return total;
    }

    protected void boundsCheck(int partition) {
        if (partition < 0 || partition >= partitions) {
            throw new IndexOutOfBoundsException(
                    "Index " + partition + " out of bounds for length " + partitions);
        }
    }

    protected int partitionIndex(int idx) {
        return ((idx + 1) * LONG_PAD) + idx;
    }

    protected int queueIndex(long idx) {
        int logicalIdx = (int) (idx % partitions);
        return (logicalIdx + 1) * POINTER_PAD_BYTES + logicalIdx;
    }

    protected int chunkIndex(long idx) {
        return (int) (idx & chunkMask) + POINTER_PAD_BYTES;
    }

    public boolean isEmpty() {
        for (int i = 0; i < partitions; i++) {
            if (!isEmpty(i)) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmpty(int partition) {
        int pIdx = partitionIndex(partition);
        long head = heads[pIdx];
        long tail = tails[pIdx];

        return head == tail;
    }

    public void reset() {
        Arrays.fill(this.heads, 0);
        Arrays.fill(this.tails, 0);

        for (int i = 0; i < this.partitions; i++) {
            int qIdx = queueIndex(i);
            Arrays.fill(this.queue[qIdx], null);
        }
    }
}
