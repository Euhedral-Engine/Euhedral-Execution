package euhedral.queues;

import euhedral.atomics.PaddedAtomicLongArray;
import euhedral.atomics.PaddedAtomicReferenceArray;
import euhedral.queues.common.PartitionedQueue;
import euhedral.queues.common.QueueUtils;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;

/// A bounded, padded, partitioned, array-based queue.
///
/// This class is not thread-safe for any method. This is meant to be used by a single thread and
/// there are no visibility guarantees between 2 different threads.
@SuppressWarnings("unchecked")
public class PartitionedArrayQueue<T> implements PartitionedQueue<T> {

    protected final PaddedAtomicReferenceArray<T[]> queue;

    @Getter
    protected final int partitions;
    @Getter
    protected final int chunkSize;
    protected final int chunkMask;

    protected final PaddedAtomicLongArray heads;
    protected final PaddedAtomicLongArray tails;

    protected final boolean unbounded;
    protected final AtomicBoolean retired = new AtomicBoolean(false);

    @Getter
    long capacity;

    public PartitionedArrayQueue(int partitions, int chunkSize) {
        this(partitions, chunkSize, false);
    }

    PartitionedArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        this.partitions = partitions;
        this.chunkSize = chunkSize;
        this.chunkMask = chunkSize - 1;
        this.queue = new PaddedAtomicReferenceArray<>(partitions, false, true);

        for (int i = 0; i < partitions; i++) {
            int rIdx = this.queue.fromRawIdx(i);
            this.queue.setPlain(rIdx, (T[]) new Object[chunkSize + PaddedAtomicReferenceArray.PADDING * 2]);
        }
        this.heads = new PaddedAtomicLongArray(partitions, false, true);
        this.tails = new PaddedAtomicLongArray(partitions, false, true);
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

        int pIdx = this.heads.fromRawIdx(partition);
        int rIdx = this.queue.fromRawIdx(partition);

        long tail = this.tails.getPlain(pIdx);
        if (QueueUtils.unsignedDiff(this.heads.getPlain(pIdx), tail + 1) > this.chunkSize) {
            this.retired.setPlain(true);
            return false;
        }
        this.tails.setPlain(pIdx, tail + 1);

        int chunkIdx = chunkIndex(tail);
        this.queue.getPlain(rIdx)[chunkIdx] = obj;
        return true;
    }

    @Override
    public T peek(int partition) {
        boundsCheck(partition);
        int pIdx = this.heads.fromRawIdx(partition);
        int rIdx = this.queue.fromRawIdx(partition);

        long head = this.heads.getPlain(pIdx);
        if (head == this.tails.getPlain(pIdx)) {
            return null;
        }
        return this.queue.getPlain(rIdx)[chunkIndex(head)];
    }

    @Override
    public T poll(int partition) {
        boundsCheck(partition);
        int pIdx = this.heads.fromRawIdx(partition);
        int rIdx = this.queue.fromRawIdx(partition);

        long head = this.heads.getPlain(pIdx);
        if (head == this.tails.getPlain(pIdx)) {
            return null;
        }
        this.heads.setPlain(pIdx, head + 1);

        return this.queue.getPlain(rIdx)[chunkIndex(head)];
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
        for (int i = 0; i < this.partitions && total < limit; i++) {
            total += drain(i, consumer, limit - total);
        }
        return total;
    }

    @Override
    public int drain(int partition, QueueConsumer<T> consumer, int limit) {
        boundsCheck(partition);
        int pIdx = this.heads.fromRawIdx(partition);
        int rIdx = this.queue.fromRawIdx(partition);

        int total = 0;
        T[] queue = this.queue.getPlain(rIdx);
        long head = this.heads.getPlain(pIdx);
        while (total < limit && head < this.tails.getPlain(pIdx)) {
            int chunkIdx = chunkIndex(head++);
            this.heads.setPlain(pIdx, head);
            consumer.consume(queue[chunkIdx]);
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

    protected int chunkIndex(long idx) {
        return (int) (idx & chunkMask) + PaddedAtomicReferenceArray.PADDING;
    }

    public boolean isRetired() {
        return retired.getPlain();
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
        int pIdx = this.heads.fromRawIdx(partition);
        long head = this.heads.getPlain(pIdx);
        long tail = this.tails.getPlain(pIdx);

        return head == tail;
    }

    public void reset() {
        this.heads.fillPlain(0);
        this.tails.fillPlain(0);

        for (int i = 0; i < this.partitions; i++) {
            int rIdx = this.queue.fromRawIdx(i);
            Arrays.fill(this.queue.getPlain(rIdx), null);
        }
    }
}
