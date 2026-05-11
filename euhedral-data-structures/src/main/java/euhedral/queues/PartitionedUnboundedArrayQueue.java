package euhedral.queues;

import static euhedral.queues.common.QueueUtils.POINTER_PAD_BYTES;

import euhedral.atomics.PaddedAtomicReference;
import euhedral.queues.common.NodeRecycler;
import euhedral.queues.common.PartitionedQueue;
import euhedral.queues.common.QueueNode;
import euhedral.queues.common.QueueNode.Type;
import euhedral.queues.common.QueueUtils;

/// A plain unbounded array queue with partitions. This class is not thread-safe for any method.
/// This is meant to be used by a single thread, and is not an SPSC queue. There are no visibility
/// guarantees between 2 different threads.
///
/// This class is also used as a base for the other unbounded types. They override specific methods
/// to make them thread-safe.
///
/// The underlying queue used by this class is the [PartitionedArrayQueue]. This class wraps
/// instances of that queue in node objects to create a linked-list of them to grow. If recycling is
/// enabled, nodes are put in the [NodeRecycler] and reused later.
///
/// @param <T> Type to store
public class PartitionedUnboundedArrayQueue<T> implements PartitionedQueue<T> {

    protected final int partitions;
    protected final int chunkSize;

    protected final NodeRecycler<T> recycler;

    protected final QueueNode<T>[] heads;
    protected final PaddedAtomicReference<QueueNode<T>> tail;

    protected final Type type;

    public PartitionedUnboundedArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        this(partitions, chunkSize, maxPooledChunks, Type.UNSAFE);
    }

    PartitionedUnboundedArrayQueue(int partitions, int chunkSize, int maxPooledChunks,
            QueueNode.Type type) {
        if (partitions <= 0 || chunkSize <= 0) {
            throw new IllegalArgumentException(
                    "Cannot have 0 partitions or 0 chunkSize: " + partitions + " " + chunkSize);
        }
        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        this.partitions = partitions;
        this.chunkSize = chunkSize;

        this.heads = new QueueNode[(partitions + 1) * QueueUtils.POINTER_PAD_BYTES
                + partitions];
        this.tail = new PaddedAtomicReference<>(new QueueNode<>(partitions, chunkSize, type));

        for (int i = 0; i < partitions; i++) {
            this.heads[partitionIndex(i)] = this.tail.getPlain();
        }
        this.recycler = maxPooledChunks <= 0 ? null : new NodeRecycler<>(type, maxPooledChunks);
        this.type = type;
    }

    protected int partitionIndex(int partition) {
        return (partition + 1) * POINTER_PAD_BYTES + partition;
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
        QueueNode<T> tail = getTailNode();
        do {
            accepted = tail.chunk.offer(partition, obj);

            if (!accepted && acquireTailMovePermission()) {
                try {
                    QueueNode<T> next = getTailNode();
                    if (tail != next) {
                        tail = next;
                        continue;
                    }

                    next = recycler == null ? null : recycler.pop();
                    next = next == null ? new QueueNode<>(partitions, chunkSize, type) : next;

                    setNextTailNode(tail, next);
                    tail = next;
                    setTailNode(tail);
                } finally {
                    releaseTailMovePermission();
                }
            } else if (!accepted) {
                tail = getTailNode();
            }
        } while (!accepted);
        return true;
    }

    @Override
    public T peek(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        while (true) {
            QueueNode<T> head = getHeadNode(pIdx);
            T val = head.chunk.peek(partition);

            if (val == null) {
                QueueNode<T> next = getNextHeadNode(head);
                if (next != null && head.isEmpty()) {
                    moveHeadsForward(head, next);
                    continue;
                }
                return null;
            }
            return val;
        }
    }

    @Override
    public T poll(int partition) {
        boundsCheck(partition);

        int pIdx = partitionIndex(partition);
        while (true) {
            QueueNode<T> head = getHeadNode(pIdx);
            T val = head.chunk.poll(partition);

            if (val == null) {
                QueueNode<T> next = getNextHeadNode(head);
                if (next != null && head.isEmpty()) {
                    moveHeadsForward(head, next);
                    continue;
                }
                return null;
            }
            return val;
        }
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
        QueueNode<T> head = getHeadNode(pIdx);
        do {
            int count = head.chunk.drain(partition, consumer, limit);

            QueueNode<T> next;
            if (count > 0) {
                limit -= count;
                total += count;
            } else if ((next = getNextHeadNode(head)) != null && head.isEmpty()) {
                moveHeadsForward(head, next);
                head = next;
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

    protected QueueNode<T> getTailNode() {
        return this.tail.getPlain();
    }

    protected boolean acquireTailMovePermission() {
        return true;
    }

    protected void setNextTailNode(QueueNode<T> tail, QueueNode<T> next) {
        tail.next.setPlain(next);
    }

    protected void releaseTailMovePermission() {
    }

    protected void setTailNode(QueueNode<T> tail) {
        this.tail.setPlain(tail);
    }

    protected QueueNode<T> getHeadNode(int pIdx) {
        return this.heads[pIdx];
    }

    protected QueueNode<T> getNextHeadNode(QueueNode<T> head) {
        return head.next.getPlain();
    }

    protected void moveHeadsForward(QueueNode<T> commonHead, QueueNode<T> nextHead) {
        for (int i = 0; i < this.partitions; i++) {
            if (this.heads[i] == commonHead) {
                this.heads[i] = nextHead;
                commonHead.refs[i] = false;
            } else if (commonHead.refs[i]) {
                return;
            }
        }

        if (recycler != null) {
            commonHead.reclaimed.setPlain(true);
            recycler.recycle(commonHead);
        }
    }
}
