package euhedral.queues;

import euhedral.queues.QueueNode.Type;

/// ## An unbounded MPMC array queue with partitions.
///
/// This class is thread-safe for any offer method. It is not thread-safe for peek, poll, or drain.
/// It is derived from [ConcurrentPartitionedUnboundedArrayQueue] but overrides the logic for head
/// and tail interaction to make it safe for use as an MPSC.
///
/// @param <T> Type to store
public final class PartitionedUnboundedMpscArrayQueue<T> extends
        ConcurrentPartitionedUnboundedArrayQueue<T> {

    public PartitionedUnboundedMpscArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions, chunkSize, maxPooledChunks, Type.MPSC);
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
        return super.headPointers.getOpaque(hpIdx);
    }

    @Override
    protected QueueNode<T> getNextHeadNode(QueueNode<T> head) {
        return head.next.getOpaque();
    }

    @Override
    protected void setNextHeadNode(int hpIdx, QueueNode<T> next) {
        this.headPointers.setOpaque(hpIdx, next);
    }
}
