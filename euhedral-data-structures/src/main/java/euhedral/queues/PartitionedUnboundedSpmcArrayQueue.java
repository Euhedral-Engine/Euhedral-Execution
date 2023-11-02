package euhedral.queues;

import euhedral.queues.QueueNode.Type;

/// ## An unbounded SPMC array queue with partitions.
///
/// This class is thread-safe for any drain, poll, or peek method. It is not thread-safe for offer. It is derived
/// from [ConcurrentPartitionedUnboundedArrayQueue] but overrides the logic for head and tail
/// interaction to make it safe for use as an SPMC.
///
/// @param <T> Type to store
public final class PartitionedUnboundedSpmcArrayQueue<T> extends
        ConcurrentPartitionedUnboundedArrayQueue<T> {

    public PartitionedUnboundedSpmcArrayQueue(int chunkSize) {
        this(1, chunkSize, 0);
    }

    public PartitionedUnboundedSpmcArrayQueue(int partitions, int chunkSize) {
        this(partitions, chunkSize, 0);
    }

    public PartitionedUnboundedSpmcArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions, chunkSize, maxPooledChunks, Type.SPMC);
    }

    // ----- Head -----

    @Override
    protected QueueNode<T> getHeadNode(int hpIdx) {
        return super.headQueue.peek(hpIdx);
    }

    @Override
    protected QueueNode<T> getNextHeadNode(QueueNode<T> head) {
        return head.next.getAcquire();
    }

    @Override
    protected void setNextHeadNode(int hpIdx, QueueNode<T> next) {
        super.headQueue.poll(hpIdx);
        while (!super.headQueue.offer(hpIdx, next)) {
            Thread.onSpinWait();
        }
    }
}
