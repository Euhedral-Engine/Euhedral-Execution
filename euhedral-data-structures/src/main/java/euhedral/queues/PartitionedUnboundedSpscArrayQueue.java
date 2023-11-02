package euhedral.queues;

import euhedral.queues.QueueNode.Type;

/// ## An unbounded SPSC array queue with partitions.
///
/// It is derived from [ConcurrentPartitionedUnboundedArrayQueue] but overrides the logic for head
/// and tail interaction to ensure visibility of changes to outside observers.
///
/// @param <T> Type to store
public final class PartitionedUnboundedSpscArrayQueue<T> extends
        ConcurrentPartitionedUnboundedArrayQueue<T> {

    public PartitionedUnboundedSpscArrayQueue(int chunkSize) {
        this(1, chunkSize, 0);
    }

    public PartitionedUnboundedSpscArrayQueue(int partitions, int chunkSize) {
        this(partitions, chunkSize, 0);
    }

    public PartitionedUnboundedSpscArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions, chunkSize, maxPooledChunks, Type.SPSC);
    }

    // ----- Head -----

    @Override
    protected QueueNode<T> getHeadNode(int hpIdx) {
        return super.headPointers.getOpaque(hpIdx);
    }

    @Override
    protected QueueNode<T> getNextHeadNode(QueueNode<T> head) {
        return head.next.getOpaque();
    }

    @Override
    protected void setNextHeadNode(int hpIdx, QueueNode<T> next) {
        super.headPointers.setOpaque(hpIdx, next);
    }
}
