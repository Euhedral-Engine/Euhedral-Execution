package euhedral.queues;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class PartitionedUnboundedMpmcArrayQueue<T> implements PartitionedQueue<T> {
    private static final VarHandle HEADS = MethodHandles.arrayElementVarHandle(QueueNode[].class);

    private final int partitions;
    private final int chunkSize;

    private final MpmcNodeRecycler<T> recycler;

    private final QueueNode<T>[] heads;
    private volatile QueueNode<T> tail;

    @SuppressWarnings("unchecked")
    public PartitionedUnboundedMpmcArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        if (partitions <= 0 || chunkSize <= 0) {
            throw new IllegalArgumentException(
                    "Cannot have 0 partitions or 0 chunkSize: " + partitions + " " + chunkSize);
        }
        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        this.partitions = partitions;
        this.chunkSize = chunkSize;

        this.heads = new QueueNode[(partitions + 1) * QueueUtils.POINTER_PAD_BYTES
                + partitions];
        this.tail = new QueueNode<>(partitions, chunkSize);

        for (int i = 0; i < partitions; i++) {
            heads[partitionIndex(i)] = this.tail;
        }
        recycler = maxPooledChunks <= 0 ? null : new MpmcNodeRecycler<>(maxPooledChunks);
    }

    /// Offers the object to a random partition
    @Override
    public boolean offer(long randomSeed, T obj) {
        int partition = (int) QueueUtils.unsignedMultiplyHigh(randomSeed, partitions);
        return offer(partition, obj);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean offer(int partition, T obj) {
        boundsCheck(partition);
        if (obj == null) {
            throw new NullPointerException();
        }

        QueueNode<T> temp = null;
        int pIdx = partitionIndex(partition);

        boolean accepted;
        QueueNode<T> tail = this.tail;
        do {
            accepted = tail.chunk.uncheckedOffer(pIdx, obj);
            if (!accepted && temp == null) {
                temp = recycler == null ? null : recycler.pop();
                temp = temp == null ? new QueueNode<>(partitions, chunkSize) : temp;
            }
            if (!accepted) {
                QueueNode<T> prev = tail;
                tail = (QueueNode<T>) QueueNode.NEXT.compareAndExchange(tail, null, temp);
                if (tail == null) {
                    tail = temp;

                    if (this.tail == prev) {
                        this.tail = tail;
                    }
                }
            }

        } while (!accepted);
        return true;
    }

    /// Drains from all partitions starting from 0
    @Override
    public int drain(T[] buffer, int offset, int limit) {
        if (buffer == null || offset >= buffer.length || limit <= 0) {
            return 0;
        }
        if (offset < 0) {
            throw new ArrayIndexOutOfBoundsException(
                    "Offset " + offset + " out of bounds for length " + buffer.length);
        }

        int total = 0;
        for (int i = 0; i < this.partitions && total < limit; i++) {
            int count = uncheckedDrain(partitionIndex(i), buffer, offset, limit);
            limit -= count;
            offset += count;
        }
        return total;
    }

    /// Drains from a specific partition
    @Override
    public int drain(int partition, T[] buffer, int offset, int limit) {
        boundsCheck(partition);
        if (buffer == null || offset >= buffer.length || limit <= 0) {
            return 0;
        }
        if (offset < 0) {
            throw new ArrayIndexOutOfBoundsException(
                    "Offset " + offset + " out of bounds for length " + buffer.length);
        }

        return uncheckedDrain(partition, buffer, offset, limit);
    }

    @SuppressWarnings("unchecked")
    private int uncheckedDrain(int partition, T[] buffer, int offset, int limit) {
        int pIdx = partitionIndex(partition);
        int total = 0;
        QueueNode<T> head = (QueueNode<T>) HEADS.getVolatile(heads, pIdx);
        do {
            int count = head.chunk.uncheckedDrain(pIdx, buffer, offset, limit);

            QueueNode<T> next;
            if (count > 0) {
                limit -= count;
                offset += count;
                total += count;
            } else if ((next = head.next) != null && head.chunk.isDrained(partition)) {
                QueueNode<T> prev = head;
                head = (QueueNode<T>) HEADS.compareAndExchange(heads, pIdx, head, next);
                QueueNode.B_ARRAY.setVolatile(prev.refs, partition, false);

                if (recycler != null && prev.isRetired() && prev.reclaimed.compareAndSet(false, true)) {
                    recycler.recycle(prev);
                }
            } else {
                break;
            }
        } while (limit > 0);
        return total;
    }

    private void boundsCheck(int idx) {
        if (idx < 0 || idx >= partitions) {
            throw new IndexOutOfBoundsException(
                    "Index " + idx + " out of bounds for length " + partitions);
        }
    }

    private int partitionIndex(int idx) {
        return (idx << QueueUtils.LONG_PAD) + QueueUtils.LONG_PAD;
    }
}
