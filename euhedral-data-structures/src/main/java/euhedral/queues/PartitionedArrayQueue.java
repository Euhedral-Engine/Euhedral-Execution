package euhedral.queues;

import euhedral.atomics.PaddedAtomicReferenceArray;
import euhedral.atomics.PaddedLongAdder;
import euhedral.queues.common.PartitionedQueue;
import euhedral.queues.common.QueueUtils;
import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;

/// A bounded, padded, partitioned, array-based queue.
///
/// This class is not thread-safe for any method. This is meant to be used by a single thread and
/// there are no visibility guarantees between 2 different threads.
@SuppressWarnings("unchecked")
public class PartitionedArrayQueue<T> extends AbstractQueue<T> implements PartitionedQueue<T> {

    protected final PaddedAtomicReferenceArray<T[]> queue;

    @Getter
    protected final int partitions;
    @Getter
    protected final int chunkSize;
    protected final int chunkMask;

    protected final PaddedLongAdder heads;
    protected final PaddedLongAdder tails;

    protected final boolean unbounded;
    protected final AtomicBoolean retired = new AtomicBoolean(false);

    long capacity;

    public PartitionedArrayQueue(int chunkSize) {
        this(1, chunkSize, false);
    }

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
            this.queue.setPlain(rIdx,
                    (T[]) new Object[chunkSize + PaddedAtomicReferenceArray.PADDING * 2]);
        }
        this.heads = new PaddedLongAdder(partitions, false, true);
        this.tails = new PaddedLongAdder(partitions, false, true);
        this.unbounded = unbounded;
        this.capacity = (long) chunkSize * partitions;
    }

    /// Offers the object to a random partition.
    ///
    /// @return success
    @Override
    public boolean offer(T obj) {
        if (this.partitions == 1) {
            return offer(0, obj);
        }
        return offer(ThreadLocalRandom.current().nextLong(), obj);
    }

    /// Offers the object to a random partition based on the seed. If the seed does not change, the
    /// same partition will be picked.
    ///
    /// @return success
    @Override
    public boolean offer(long randomSeed, T obj) {
        int partition = (int) QueueUtils.unsignedMultiplyHigh(randomSeed, this.partitions);
        return offer(partition, obj);
    }

    /// Offers the object to a specific partition
    ///
    /// @return success
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

    /// Gets, but does not remove, the head of the first partition that does not return null.
    ///
    /// @return The head of a partition. `null` if completely empty
    @Override
    public T peek() {
        for (int i = 0; i < this.partitions; i++) {
            T top = peek(i);
            if (top != null) {
                return top;
            }
        }
        return null;
    }

    /// Gets, but does not remove, the object at the front of the partition.
    ///
    /// @return Object at the front of the partition. `null` if empty
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

    /// Gets and removes the head of the first partition that returns a non-null value.
    ///
    /// @return the head of a partition. `null` if empty
    @Override
    public T poll() {
        for (int i = 0; i < this.partitions; i++) {
            T top = poll(i);
            if (top != null) {
                return top;
            }
        }
        return null;
    }

    /// Gets and removes the object at the front of the partition.
    ///
    /// @return Object at the front of the partition. `null` if empty
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

    /// Drains from all partitions starting from 0 up to the limit
    ///
    /// @param consumer Consumer to give items to
    /// @param limit Maximum number of items to pull
    /// @return Number of items drained
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

    /// Drains from a specific partition.
    ///
    /// @return Number of items drained
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

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean isEmpty(int partition) {
        int pIdx = this.heads.fromRawIdx(partition);
        long head = this.heads.getPlain(pIdx);
        long tail = this.tails.getPlain(pIdx);

        return head == tail;
    }

    @Override
    public long sizeLong() {
        long tails = this.tails.sum();
        long heads = this.heads.sum();
        return QueueUtils.unsignedDiff(heads, tails);
    }

    @Override
    public long size(int partition) {
        int pIdx = this.heads.fromRawIdx(partition);
        long tail = this.tails.getPlain(pIdx);
        long head = this.heads.getPlain(pIdx);
        return QueueUtils.unsignedDiff(head, tail);
    }

    @Override
    public int partitions() {
        return this.partitions;
    }

    @Override
    public long capacity() {
        return this.capacity;
    }

    /// Completely resets all partitions to their initial state.
    @Override
    public void clear() {
        purge();
    }

    /// Completely resets all partitions to their initial state.
    @Override
    public void purge() {
        this.heads.fillPlain(0);
        this.tails.fillPlain(0);

        for (int i = 0; i < this.partitions; i++) {
            int rIdx = this.queue.fromRawIdx(i);
            Arrays.fill(this.queue.getPlain(rIdx), null);
        }
        this.retired.setPlain(false);
    }

    // ----- Queue<T> Interface -----

    /// Inserts the specified object into this queue if it is possible to do so
    /// immediately without violating capacity restrictions, returning
    /// `true` upon success and throwing an `IllegalStateException`
    /// if no space is currently available.
    ///
    /// @param obj the object to add
    /// @return `true` (as specified by [Collection#add])
    /// @throws IllegalStateException if the object cannot be added at this
    ///         time due to capacity restrictions
    @Override
    public boolean add(T obj) {
        for (int i = 0; i < this.partitions; i++) {
            if(offer(i, obj)) {
                return true;
            }
        }
        throw new IllegalStateException("Queue full");
    }

    /// Not supported
    ///
    /// @throws UnsupportedOperationException
    @Override
    public Iterator<T> iterator() {
        throw new UnsupportedOperationException("iterator");
    }

    @Override
    public int size() {
        long size = this.sizeLong();
        if(size > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) size;
    }
}
