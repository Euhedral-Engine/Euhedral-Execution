package euhedral.queues;

import static euhedral.queues.QueueUtils.POINTER_PAD_BYTES;

import euhedral.atomics.PaddedAtomicReference;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("unchecked")
public class PartitionedUnboundedMpmcArrayQueue<T> implements PartitionedQueue<T> {
    private static final VarHandle HEADS = MethodHandles.arrayElementVarHandle(QueueNode[].class);

    private final int partitions;
    private final int chunkSize;

    private final MpmcNodeRecycler<T> recycler;

    private final QueueNode<T>[] heads;
    private final PaddedAtomicReference<QueueNode<T>> tail;
    private final AtomicBoolean movingTail = new AtomicBoolean(false);

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
        this.tail = new PaddedAtomicReference<>(new QueueNode<>(partitions, chunkSize));

        for (int i = 0; i < partitions; i++) {
            heads[partitionIndex(i)] = this.tail.getPlain();
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
    public boolean offer(int partition, T obj) {
        boundsCheck(partition);
        if (obj == null) {
            throw new NullPointerException();
        }

        boolean accepted;
        QueueNode<T> tail = this.tail.getAcquire();
        do {
            accepted = tail.chunk.offer(partition, obj);

            if (!accepted && movingTail.compareAndSet(false, true)) {
                try {
                    QueueNode<T> next = this.tail.getAcquire();
                    if(tail != next) {
                        tail = next;
                        continue;
                    }

                    next = recycler == null ? null : recycler.pop();
                    next = next == null ? new QueueNode<>(partitions, chunkSize) : next;

                    tail.next = next;
                    tail = next;
                    this.tail.setRelease(tail);
                } finally {
                    this.movingTail.set(false);
                }
            } else if(!accepted) {
                tail = this.tail.getAcquire();
            }
        } while (!accepted);
        return true;
    }

    @Override
    public T peek(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        QueueNode<T> head = (QueueNode<T>) HEADS.getAcquire(heads, pIdx);
        return head.chunk.peek(partition);
    }

    @Override
    public T poll(int partition) {
        boundsCheck(partition);

        int pIdx = partitionIndex(partition);
        QueueNode<T> head = (QueueNode<T>) HEADS.getAcquire(heads, pIdx);
        T val = head.chunk.poll(partition);

        if (val == null) {
            QueueNode<T> next = head.next;
            if(next != null && head.isEmpty()) {
                moveHeadsForward(head, next);
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
            total += count;
        }
        return total;
    }

    /// Drains from a specific partition
    @Override
    public int drain(int partition, QueueConsumer<T> consumer, int limit) {
        boundsCheck(partition);
        if (consumer == null || limit <= 0) {
            return 0;
        }

        int pIdx = partitionIndex(partition);
        int total = 0;
        QueueNode<T> head = (QueueNode<T>) HEADS.getAcquire(heads, pIdx);
        do {
            int count = head.chunk.drain(partition, consumer, limit);

            QueueNode<T> next;
            if (count > 0) {
                limit -= count;
                total += count;
            } else if ((next = head.next) != null && head.isEmpty()) {
                moveHeadsForward(head, next);
                head = next;
            } else {
                break;
            }
        } while (limit > 0);
        return total;
    }

    private void moveHeadsForward(QueueNode<T> commonHead, QueueNode<T> nextHead) {
        int count = 0;
        for(int i = 0; i < this.partitions; i++) {
            int pIdx = partitionIndex(i);

            if(HEADS.compareAndSet(this.heads, pIdx, commonHead, nextHead)) {
                QueueNode.B_ARRAY.setRelease(commonHead.refs, i, false);
                count++;
            } else if(!((boolean) QueueNode.B_ARRAY.getAcquire(commonHead.refs, i))) {
                count++;
            }
        }
        if(count != this.partitions) {
            return;
        }

        if(recycler != null && commonHead.reclaimed.compareAndSet(false, true)) {
            recycler.recycle(commonHead);
        }
    }

    private void boundsCheck(int idx) {
        if (idx < 0 || idx >= partitions) {
            throw new IndexOutOfBoundsException(
                    "Index " + idx + " out of bounds for length " + partitions);
        }
    }

    private int partitionIndex(int idx) {
        int logicalIdx = idx % partitions;
        return (logicalIdx + 1) * POINTER_PAD_BYTES + logicalIdx;
    }
}
