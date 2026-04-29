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

        boolean accepted;
        QueueNode<T> tail = this.tail;
        do {
            accepted = tail.chunk.offer(partition, obj);
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

    @Override
    @SuppressWarnings("unchecked")
    public T peek(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        QueueNode<T> head = (QueueNode<T>) HEADS.getVolatile(heads, pIdx);
        return head.chunk.peek(partition);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T poll(int partition) {
        boundsCheck(partition);

        int pIdx = partitionIndex(partition);
        QueueNode<T> head = (QueueNode<T>) HEADS.getVolatile(heads, pIdx);
        T val = head.chunk.poll(partition);

        QueueNode<T> next = head.next;
        if (next != null && head.chunk.isEmpty(partition) && HEADS.compareAndSet(heads, pIdx, head, next)) {
            QueueNode.B_ARRAY.setVolatile(head.refs, partition, false);

            if (recycler != null && head.isRetired() && head.reclaimed.compareAndSet(false, true)) {
                recycler.recycle(head);
            }
        }
        return val;
    }

    /// Drains from all partitions starting from 0
    @Override
    public int drain(QueueConsumer<T> consumer, int limit) {
        if (consumer == null || limit <= 0) {
            return 0;
        }

        int total = 0;
        for (int i = 0; i < this.partitions && total < limit; i++) {
            int count = drain(i, consumer, limit);
            limit -= count;
        }
        return total;
    }

    /// Drains from a specific partition
    @Override
    @SuppressWarnings("unchecked")
    public int drain(int partition, QueueConsumer<T> consumer, int limit) {
        boundsCheck(partition);
        if (consumer == null || limit <= 0) {
            return 0;
        }

        int pIdx = partitionIndex(partition);
        int total = 0;
        QueueNode<T> head = (QueueNode<T>) HEADS.getVolatile(heads, pIdx);
        do {
            int count = head.chunk.drain(partition, consumer, limit);

            QueueNode<T> next;
            if (count > 0) {
                limit -= count;
                total += count;
            } else if ((next = head.next) != null && head.chunk.isEmpty(partition)) {
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
