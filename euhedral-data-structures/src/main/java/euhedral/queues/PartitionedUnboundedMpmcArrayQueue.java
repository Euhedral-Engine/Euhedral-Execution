package euhedral.queues;

import euhedral.queues.QueueNode.Type;

/// ## An unbounded MPMC array queue with partitions.
///
///  This class is thread-safe for any method. It is derived from
/// [ConcurrentPartitionedUnboundedArrayQueue] but overrides the logic for head and tail interaction
/// to make it safe for use as an MPMC.
///
/// @param <T> Type to store
public final class PartitionedUnboundedMpmcArrayQueue<T> extends
        ConcurrentPartitionedUnboundedArrayQueue<T> {

    public PartitionedUnboundedMpmcArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions, chunkSize, maxPooledChunks, Type.MPMC);
    }

    // ----- Tail -----

    @Override
    protected long getTailEpoch() {
        return super.tailEpoch.getAcquire();
    }

    @Override
    protected boolean casTailEpoch(long oldEpoch, long newEpoch) {
        return super.tailEpoch.compareAndSet(oldEpoch, newEpoch);
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
