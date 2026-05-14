package euhedral.queues;

import euhedral.atomics.PaddedAtomicReferenceArray;
import euhedral.queues.QueueNode.Type;
import euhedral.queues.common.NodeRecycler;
import euhedral.queues.common.PartitionedQueue;
import euhedral.queues.common.QueueUtils;
import java.lang.invoke.VarHandle;
import java.util.StringJoiner;
import lombok.Getter;

/// A plain unbounded array queue with partitions. This class is not thread-safe for any method.
/// There are no visibility guarantees between 2 different threads.
///
/// The underlying queue used by this class is the [PartitionedArrayQueue]. This class wraps
/// instances of that queue type in [QueueNode] objects to create a linked-list of them. If
/// recycling is enabled, nodes are put in the [NodeRecycler] and reused later.
///
/// @param <T> Type to store
public sealed class PartitionedUnboundedArrayQueue<T> implements PartitionedQueue<T> permits
        ConcurrentPartitionedUnboundedArrayQueue {

    @Getter
    protected final int partitions;
    @Getter
    protected final int chunkSize;

    protected final NodeRecycler<T> recycler;

    protected final PaddedAtomicReferenceArray<QueueNode<T>> headPointers;

    private QueueNode<T> tailPtr;

    public PartitionedUnboundedArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        if (partitions <= 0 || chunkSize <= 0) {
            throw new IllegalArgumentException(
                    "Cannot have 0 partitions or 0 chunkSize: " + partitions + " " + chunkSize);
        }
        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        chunkSize = Math.max(1, chunkSize);
        this.partitions = partitions;
        this.chunkSize = chunkSize;
        this.recycler =
                maxPooledChunks <= 0 ? null : new NodeRecycler<>(Type.PLAIN, maxPooledChunks);

        this.tailPtr = new QueueNode<>(partitions, chunkSize, Type.PLAIN);

        this.headPointers = new PaddedAtomicReferenceArray<>(partitions, false, false);
        this.headPointers.fill(tailPtr);
    }

    protected PartitionedUnboundedArrayQueue(int partitions, int chunkSize,
            NodeRecycler<T> recycler, PaddedAtomicReferenceArray<QueueNode<T>> headPointers) {
        if (partitions <= 0 || chunkSize <= 0) {
            throw new IllegalArgumentException(
                    "Cannot have 0 partitions or 0 chunkSize: " + partitions + " " + chunkSize);
        }
        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        chunkSize = Math.max(1, chunkSize);

        this.partitions = partitions;
        this.chunkSize = chunkSize;
        this.recycler = recycler;
        this.tailPtr = null;
        this.headPointers = headPointers;
    }

    /// Offers the object to a random partition based on the seed. If the seed does not change, the
    /// same partition will be picked.
    ///
    /// @return success
    @Override
    public final boolean offer(long randomSeed, T obj) {
        int partition = (int) QueueUtils.unsignedMultiplyHigh(randomSeed, partitions);
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

        while (!this.tailPtr.offer(partition, obj)) {
            moveTailForward();
        }
        return true;
    }

    /// Gets the object at the top of the partition.
    @Override
    public T peek(int partition) {
        boundsCheck(partition);
        int hpIdx = this.headPointers.fromRawIdx(partition);
        while (true) {
            QueueNode<T> head = getHeadNode(hpIdx);

            T val = head.peek(partition);
            if (val == null) {
                if (!head.isRetired()) {
                    return null;
                }

                QueueNode<T> next = getNextHeadNode(head);
                if (next != null) {
                    long epoch = head.getHeadEpoch(partition);
                    moveHeadsForward(epoch, head, next);
                    continue;
                }
                return null;
            }
            return val;
        }
    }

    /// Gets and removes the object at the top of the partition
    @Override
    public T poll(int partition) {
        boundsCheck(partition);

        int  hpIdx = this.headPointers.fromRawIdx(partition);
        while (true) {
            QueueNode<T> head = getHeadNode(hpIdx);
            T val = head.poll(partition);

            if (val == null) {
                if (!head.isRetired()) {
                    return null;
                }

                QueueNode<T> next = getNextHeadNode(head);
                if (next != null) {
                    long epoch = head.getHeadEpoch(partition);
                    moveHeadsForward(epoch, head, next);
                    continue;
                }
                return null;
            }
            return val;
        }
    }

    /// Drains from all partitions starting from 0
    ///
    /// @return Number of items drained
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

        int hpIdx = this.headPointers.fromRawIdx(partition);
        while (limit > 0) {
            QueueNode<T> head = getHeadNode(hpIdx);

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
                long epoch = head.getHeadEpoch(partition);
                moveHeadsForward(epoch, head, next);
            } else {
                break;
            }
        }
        return total;
    }

    // ----- Tail -----

    protected void moveTailForward() {
        QueueNode<T> next;
        next = recycler == null ? null : recycler.pop();
        next = next == null ? new QueueNode<>(partitions, chunkSize, Type.PLAIN) : next;

        this.tailPtr.next.setPlain(next);

        this.tailPtr = next;
    }

    // ----- Head -----

    protected QueueNode<T> getHeadNode(int hpIdx) {
        return this.headPointers.getPlain(hpIdx);
    }

    protected QueueNode<T> getNextHeadNode(QueueNode<T> head) {
        return head.next.getPlain();
    }

    protected void setNextHeadNode(int hpIdx, QueueNode<T> next) {
        this.headPointers.setPlain(hpIdx, next);
    }

    protected void moveHeadsForward(long epoch, QueueNode<T> commonHead, QueueNode<T> nextHead) {
        int flipped = 0;
        int count = 0;
        for (int i = 0; i < this.partitions; i++) {
            if (!commonHead.isEmpty(i)) {
                continue;
            }

            int hpIdx = this.headPointers.fromRawIdx(i);
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
        if (this.recycler == null || count != this.partitions || flipped == 0) {
            return;
        }

        this.recycler.recycle(commonHead);
    }

    protected final void boundsCheck(int idx) {
        if (idx < 0 || idx >= partitions) {
            throw new IndexOutOfBoundsException(
                    "Index " + idx + " out of bounds for length " + partitions);
        }
    }

    @Override
    public boolean isEmpty() {
        for(int i = 0; i < this.partitions; i++) {
            int pIdx = this.headPointers.fromRawIdx(i);
            if(!this.headPointers.getPlain(pIdx).isEmpty(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isEmpty(int partition) {
        int pIdx = this.headPointers.fromRawIdx(partition);
        return this.headPointers.getPlain(pIdx).isEmpty(partition);
    }

    @Override
    public long size() {
        long sum = 0;
        for(int i = 0; i < this.partitions; i++) {
            sum += size(i);
        }
        return sum;
    }

    @Override
    public long size(int partition) {
        int rIdx = this.headPointers.fromRawIdx(partition);
        QueueNode<T> head = this.headPointers.getPlain(rIdx);

        long sum = 0;
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
        for (int i = 0; i < this.partitions; i++) {
            int hpIdx = this.headPointers.fromRawIdx(i);
            QueueNode<T> head = getHeadNode(hpIdx);
            sj.add(String.format("Head: %s", head));
        }
        sj.add(String.format("\nTail: %s", this.tailPtr));
        return sj.toString();
    }
}
