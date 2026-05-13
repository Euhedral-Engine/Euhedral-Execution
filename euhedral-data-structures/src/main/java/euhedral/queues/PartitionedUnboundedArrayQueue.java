package euhedral.queues;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.atomics.PaddedAtomicReferenceArray;
import euhedral.queues.QueueNode.Type;
import euhedral.queues.common.NodeRecycler;
import euhedral.queues.common.PartitionedQueue;
import euhedral.queues.common.QueueUtils;
import java.lang.invoke.VarHandle;
import java.util.StringJoiner;
import lombok.Getter;

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
public sealed class PartitionedUnboundedArrayQueue<T> implements PartitionedQueue<T> permits
        PartitionedUnboundedMpmcArrayQueue, PartitionedUnboundedMpscArrayQueue,
        PartitionedUnboundedSpmcArrayQueue {

    protected final Type type;
    @Getter
    private final int partitions;
    @Getter
    private final int chunkSize;
    private final NodeRecycler<T> recycler;

    private final PaddedAtomicLong headLock;
    private final PaddedAtomicReferenceArray<QueueNode<T>> heads;
    private final PartitionedArrayQueue<QueueNode<T>> headQueues;

    private final PaddedAtomicLong tailEpoch;
    private final PartitionedArrayQueue<QueueNode<T>> tailQueue;

    public PartitionedUnboundedArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        this(partitions, chunkSize, maxPooledChunks, Type.PLAIN);
    }

    PartitionedUnboundedArrayQueue(int partitions, int chunkSize, int maxPooledChunks,
            QueueNode.Type type) {
        if (partitions <= 0 || chunkSize <= 0) {
            throw new IllegalArgumentException(
                    "Cannot have 0 partitions or 0 chunkSize: " + partitions + " " + chunkSize);
        }
        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        chunkSize = Math.max(1, chunkSize);
        this.partitions = partitions;
        this.chunkSize = chunkSize;
        this.type = type;
        this.recycler = maxPooledChunks <= 0 ? null : new NodeRecycler<>(type, maxPooledChunks);

        QueueNode<T> tail = new QueueNode<>(partitions, chunkSize, type);

        this.headLock = switch (type) {
            case PLAIN, SPSC, MPSC -> null;
            default -> new PaddedAtomicLong(0);
        };
        this.headQueues = switch (type) {
            case PLAIN, SPSC, MPSC -> {
                this.heads = new PaddedAtomicReferenceArray<>(partitions, false, false);
                this.heads.fill(tail);
                yield null;
            }
            default -> {
                this.heads = null;
                PartitionedMpmcArrayQueue<QueueNode<T>> heads = new PartitionedMpmcArrayQueue<>(
                        partitions, 64);
                for (int i = 0; i < partitions; i++) {
                    heads.offer(i, tail);
                }
                yield heads;
            }
        };

        this.tailQueue = switch (type) {
            case PLAIN -> {
                this.tailEpoch = null;
                yield new PartitionedArrayQueue<>(1, 64, false);
            }
            case SPSC, SPMC -> {
                this.tailEpoch = null;
                yield new PartitionedSpscArrayQueue<>(1, 64, false);
            }
            default -> {
                this.tailEpoch = new PaddedAtomicLong(0);
                yield new PartitionedSpmcArrayQueue<>(1, 64, false);
            }
        };
        this.tailQueue.offer(0, tail);
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

        while (true) {
            long epoch = getTailEpoch();
            QueueNode<T> tail = this.tailQueue.peek(0);
            if (tail == null || tail.getTailEpoch() != epoch) {
                continue;
            }
            if (tail.offer(partition, obj)) {
                break;
            }
            moveTailForward(epoch);
        }
        return true;
    }

    @Override
    public T peek(int partition) {
        boundsCheck(partition);
        while (true) {
            QueueNode<T> head = getHeadNode(partition);
            if (head == null) {
                continue;
            }

            long epoch = head.getHeadEpoch(partition);
            T val = head.chunk.peek(partition);

            if (val == null) {
                if (!head.isRetired()) {
                    return null;
                }

                QueueNode<T> next = getNextHeadNode(head);
                if (next != null) {
                    moveHeadsForward(epoch, head, next);
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

        while (true) {
            QueueNode<T> head = getHeadNode(partition);
            if (head == null) {
                continue;
            }

            long epoch = head.getHeadEpoch(partition);
            T val = head.chunk.poll(partition);

            if (val == null) {
                if (!head.isRetired()) {
                    return null;
                }

                QueueNode<T> next = getNextHeadNode(head);
                if (next != null) {
                    moveHeadsForward(epoch, head, next);
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

        int total = 0;

        while (limit > 0) {
            QueueNode<T> head = getHeadNode(partition);
            if (head == null) {
                continue;
            }

            long epoch = head.getHeadEpoch(partition);
            int count = head.drain(partition, consumer, limit);

            if (count > 0) {
                limit -= count;
                total += count;
                continue;
            }

            if (!head.isRetired()) {
                break;
            }

            QueueNode<T> next = getNextHeadNode(head);
            if (next != null) {
                moveHeadsForward(epoch, head, next);
            } else {
                break;
            }
        }
        return total;
    }

    // ----- Tail -----

    private void setNextTailNode(QueueNode<T> tail, QueueNode<T> next) {
        if (this.type == Type.PLAIN) {
            tail.next.setPlain(next);
        } else {
            tail.next.setRelease(next);
        }
    }

    private long getTailEpoch() {
        return switch (this.type) {
            case MPSC, MPMC -> this.tailEpoch.getAcquire();
            default -> 0;
        };
    }

    private boolean casTailEpoch(long oldEpoch, long newEpoch) {
        return switch (this.type) {
            case MPSC, MPMC -> this.tailEpoch.compareAndSet(oldEpoch, newEpoch);
            default -> true;
        };
    }

    private void moveTailForward(long epoch) {
        if (casTailEpoch(epoch, epoch + 1)) {
            QueueNode<T> tail = this.tailQueue.poll(0);

            QueueNode<T> next;
            next = recycler == null ? null : recycler.pop();
            next = next == null ? new QueueNode<>(partitions, chunkSize, type) : next;
            next.setTailEpoch(epoch + 1);

            setNextTailNode(tail, next);

            VarHandle.releaseFence();
            while (!this.tailQueue.offer(0, next)) {
                Thread.onSpinWait();
            }
        }
    }

    // ----- Head -----

    private QueueNode<T> getHeadNode(int partition) {
        return switch (this.type) {
            case PLAIN -> this.heads.getPlain(partition);
            case SPSC, MPSC -> this.heads.getOpaque(partition);
            default -> this.headQueues.peek(partition);
        };
    }

    private QueueNode<T> getNextHeadNode(QueueNode<T> head) {
        return switch (this.type) {
            case PLAIN -> head.next.getPlain();
            case SPSC, MPSC -> head.next.getOpaque();
            default -> head.next.getAcquire();
        };
    }

    private void setNextHeadNode(int partition, QueueNode<T> next) {
        if (this.type == Type.PLAIN) {
            this.heads.setPlain(partition, next);
            return;
        }
        if (this.type == Type.SPSC || this.type == Type.MPSC) {
            this.heads.setOpaque(partition, next);
            return;
        }
        this.headQueues.poll(partition);
        while (!this.headQueues.offer(partition, next)) {
            Thread.onSpinWait();
        }
    }

    private boolean acquireHeadLock() {
        if (this.headLock == null) {
            return true;
        }
        return this.headLock.compareAndSet(0, 1);
    }

    private void releaseHeadLock() {
        if (this.headLock == null) {
            return;
        }
        this.headLock.set(0);
    }

    private void moveHeadsForward(long epoch, QueueNode<T> commonHead, QueueNode<T> nextHead) {
        if (!acquireHeadLock()) {
            return;
        }

        try {
            int flipped = 0;
            int count = 0;
            for (int i = 0; i < this.partitions; i++) {
                if (!commonHead.isEmpty(i)) {
                    continue;
                }

                QueueNode<T> partHead = getHeadNode(i);
                if (partHead == commonHead) {
                    if (commonHead.getHeadEpoch(i) == epoch) {
                        setNextHeadNode(i, nextHead);
                        commonHead.casHeadEpoch(i, epoch, epoch + 1);
                        flipped++;
                        count++;
                    }
                } else if (commonHead.getHeadEpoch(i) == epoch + 1) {
                    count++;
                }
            }
            if (this.recycler == null || count != this.partitions || flipped == 0) {
                return;
            }

            this.recycler.recycle(commonHead);
        } finally {
            releaseHeadLock();
        }
    }

    private void boundsCheck(int idx) {
        if (idx < 0 || idx >= partitions) {
            throw new IndexOutOfBoundsException(
                    "Index " + idx + " out of bounds for length " + partitions);
        }
    }

    @Override
    public String toString() {
        StringJoiner sj = new StringJoiner("\n");
        for (int i = 0; i < this.partitions; i++) {
            QueueNode<T> head = getHeadNode(i);
            sj.add(String.format("Head: P%d ID: %d\n%s", i, head.hashCode(), head.chunk));
        }
        QueueNode<T> tail = this.tailQueue.peek(0);
        sj.add("\nTail: ID: " + tail.hashCode());
        sj.add(tail.chunk.toString());
        return sj.toString();
    }
}
