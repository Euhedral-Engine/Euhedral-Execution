package euhedral.queues;

import euhedral.queues.QueueNode.Type;

/// An unbounded multi-producer-multi-consumer array queue with partitions. This class is
/// thread-safe for any method. It is derived from [ConcurrentPartitionedUnboundedArrayQueue] but overrides
/// the logic for head and tail interaction to make it safe for use as an MPMC.
///
/// @param <T> Type to store
public final class PartitionedUnboundedMpmcArrayQueue<T> extends ConcurrentPartitionedUnboundedArrayQueue<T> {

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
    protected QueueNode<T> getHeadNode(int partition) {
        return super.headQueues.peek(partition);
    }

    @Override
    protected QueueNode<T> getNextHeadNode(QueueNode<T> head) {
        return head.next.getAcquire();
    }

    @Override
    protected void setNextHeadNode(int partition, QueueNode<T> next) {
        super.headQueues.poll(partition);
        while (!super.headQueues.offer(partition, next)) {
            Thread.onSpinWait();
        }
    }
}
