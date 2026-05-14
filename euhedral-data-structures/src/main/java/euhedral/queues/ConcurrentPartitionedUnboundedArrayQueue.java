package euhedral.queues;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.atomics.PaddedAtomicReferenceArray;
import euhedral.queues.QueueNode.Type;
import euhedral.queues.common.NodeRecycler;
import java.lang.invoke.VarHandle;
import java.util.StringJoiner;

/// A template of a concurrent unbounded array queue with partitions. This class is overriden by its
/// subclasses to selectively choose the type of thread safety between producers and consumers.
///
/// This class wraps instances of underlying queue types in [QueueNode] objects to create a
/// linked-list of them. If recycling is enabled, nodes are put in the [NodeRecycler] and reused
/// later.
///
/// @param <T> Type to store
abstract sealed class ConcurrentPartitionedUnboundedArrayQueue<T>
        extends PartitionedUnboundedArrayQueue<T>
        permits PartitionedUnboundedSpscArrayQueue, PartitionedUnboundedSpmcArrayQueue,
        PartitionedUnboundedMpscArrayQueue, PartitionedUnboundedMpmcArrayQueue {

    protected final Type type;

    protected final PaddedAtomicLong tailEpoch;
    protected final PartitionedArrayQueue<QueueNode<T>> headQueues;
    private final PartitionedArrayQueue<QueueNode<T>> tailQueue;
    private final PaddedAtomicLong headLock;


    public ConcurrentPartitionedUnboundedArrayQueue(int partitions, int chunkSize,
            int maxPooledChunks) {
        this(partitions, chunkSize, maxPooledChunks, Type.PLAIN);
    }

    ConcurrentPartitionedUnboundedArrayQueue(int partitions, int chunkSize, int maxPooledChunks,
            QueueNode.Type type) {
        super(partitions, chunkSize,
                maxPooledChunks <= 0 ? null : new NodeRecycler<>(type, maxPooledChunks),
                switch (type) {
                    case SPSC, MPSC -> new PaddedAtomicReferenceArray<>(partitions, false, false);
                    default -> null;
                });

        this.type = type;

        QueueNode<T> tail = new QueueNode<>(partitions, chunkSize, type);
        this.tailQueue = switch (type) {
            case SPSC, SPMC -> {
                this.tailEpoch = null;
                yield new PartitionedSpscArrayQueue<>(1, 64, false);
            }
            default -> {
                this.tailEpoch = new PaddedAtomicLong(0);
                yield new PartitionedSpmcArrayQueue<>(1, 64, false);
            }
        };
        while (!this.tailQueue.offer(0, tail)) {
            Thread.onSpinWait();
        }

        this.headLock = switch (type) {
            case SPSC, MPSC -> null;
            default -> new PaddedAtomicLong(0);
        };
        this.headQueues = switch (type) {
            case SPSC, MPSC -> {
                super.headPointers.fill(tail);
                yield null;
            }
            default -> {
                PartitionedMpmcArrayQueue<QueueNode<T>> heads =
                        new PartitionedMpmcArrayQueue<>(partitions, 64);
                for (int i = 0; i < partitions; i++) {
                    heads.offer(i, tail);
                }
                yield heads;
            }
        };
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
            if(tail == null) {
                Thread.onSpinWait();
                continue;
            }
            if (tail.getTailEpoch() != epoch) {
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
        int hpIdx = super.headPointers == null ? partition : super.headPointers.fromRawIdx(partition);

        while (true) {
            QueueNode<T> head = getHeadNode(hpIdx);
            while (head == null) {
                Thread.onSpinWait();
                head = getHeadNode(hpIdx);
            }

            long epoch = head.getHeadEpoch(partition);
            T val = head.peek(partition);

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

        int hpIdx = super.headPointers == null ? partition : super.headPointers.fromRawIdx(partition);
        while (true) {
            QueueNode<T> head = getHeadNode(hpIdx);
            while (head == null) {
                Thread.onSpinWait();
                head = getHeadNode(hpIdx);
            }

            long epoch = head.getHeadEpoch(partition);
            T val = head.poll(partition);

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
    public int drain(int partition, QueueConsumer<T> consumer, int limit) {
        boundsCheck(partition);
        if (consumer == null || limit <= 0) {
            return 0;
        }

        int total = 0;

        int hpIdx = super.headPointers == null ? partition : super.headPointers.fromRawIdx(partition);
        while (limit > 0) {
            QueueNode<T> head = getHeadNode(hpIdx);
            while (head == null) {
                Thread.onSpinWait();
                head = getHeadNode(hpIdx);
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

    protected long getTailEpoch() {
        return 0;
    }

    protected boolean casTailEpoch(long oldEpoch, long newEpoch) {
        return true;
    }

    private void moveTailForward(long epoch) {
        if (casTailEpoch(epoch, epoch + 1)) {
            QueueNode<T> tail = this.tailQueue.poll(0);

            QueueNode<T> next;
            next = recycler == null ? null : recycler.pop();
            next = next == null ? new QueueNode<>(partitions, chunkSize, type) : next;
            next.setTailEpoch(epoch + 1);

            tail.next.setRelease(next);

            VarHandle.releaseFence();
            while (!this.tailQueue.offer(0, next)) {
                Thread.onSpinWait();
            }
        }
    }

    // ----- Head -----

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

    @Override
    protected final void moveHeadsForward(long epoch, QueueNode<T> commonHead,
            QueueNode<T> nextHead) {
        if (!acquireHeadLock()) {
            return;
        }

        try {
            int flipped = 0;
            int count = 0;
            for (int i = 0; i < super.partitions; i++) {
                if (!commonHead.isEmpty(i)) {
                    continue;
                }

                int hpIdx = super.headPointers == null ? i : super.headPointers.fromRawIdx(i);
                QueueNode<T> partHead = getHeadNode(hpIdx);
                if (partHead == commonHead) {
                    if (commonHead.getHeadEpoch(i) == epoch) {
                        setNextHeadNode(hpIdx, nextHead);
                        commonHead.casHeadEpoch(i, epoch, epoch + 1);
                        flipped++;
                        count++;
                    }
                } else if (commonHead.getHeadEpoch(i) == epoch + 1) {
                    count++;
                }
            }
            if (super.recycler == null || count != super.partitions || flipped == 0) {
                return;
            }

            super.recycler.recycle(commonHead);
        } finally {
            releaseHeadLock();
        }
    }

    @Override
    public boolean isEmpty() {
        for(int i = 0; i < super.partitions; i++) {
            if(!isEmpty(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isEmpty(int partition) {
        if(super.headPointers != null) {
            int pIdx =  super.headPointers.fromRawIdx(partition);
            if(!super.headPointers.getAcquire(pIdx).isEmpty(partition)) {
                return false;
            }
        } else {
            QueueNode<T> q =  this.headQueues.peek(partition);
            if(q == null || q.isRetired() || !q.isEmpty(partition)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long size(int partition) {
        if(super.headPointers != null) {
            return super.size(partition);
        }

        long sum = 0;
        QueueNode<T> head = this.headQueues.peek(partition);
        while(head == null) {
            Thread.onSpinWait();
            head = this.headQueues.peek(partition);
        }
        while(head != null) {
            sum += head.size(partition);
            head = head.next.getPlain();
        }
        VarHandle.acquireFence();
        return sum;
    }

    @Override
    public String toString() {
        StringJoiner sj = new StringJoiner("\n");

        for (int i = 0; i < super.partitions; i++) {
            int hpIdx = super.headPointers == null ? i : super.headPointers.fromRawIdx(i);
            QueueNode<T> head = getHeadNode(hpIdx);
            sj.add(String.format("Head: %s", head));
            sj.add("");
        }
        QueueNode<T> tail = this.tailQueue.peek(0);
        sj.add(String.format("\nTail: %s", tail));
        return sj.toString();
    }
}
