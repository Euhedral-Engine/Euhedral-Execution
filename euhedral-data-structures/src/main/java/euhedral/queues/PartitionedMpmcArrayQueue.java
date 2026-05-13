package euhedral.queues;

/// A partitioned, padded, array-based queue. This class has two operating modes, bounded and
/// unbounded.
///
/// <b>Bounded Mode:</b>
///
/// When a partition is full, offers to that partition will be rejected until space
/// frees.
///
/// <b>Unbounded Mode:<b/>
///
/// In unbounded mode, this class is will be managed by another class i.e.
/// [PartitionedUnboundedMpmcArrayQueue]. When a partition is full and an offer is rejected, this
/// queue is marked `retired` and all subsequent offers to any partition are rejected. When the
/// managing class is ready to use the instance again, they will reset the queue to allow offers.
///
/// @param <T> Type to store
public final class PartitionedMpmcArrayQueue<T> extends ConcurrentPartitionedArrayQueue<T> {

    public PartitionedMpmcArrayQueue(int partitions, int chunkSize) {
        super(partitions, chunkSize, true, false);
    }

    PartitionedMpmcArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        super(partitions, chunkSize, true, unbounded);
    }

    @Override
    protected void incrementInFlight(int partition) {
        super.inFlight.getAndIncrement(partition);
    }

    @Override
    protected void decrementInFlight(int partition) {
        super.inFlight.getAndDecrement(partition);
    }

    @Override
    protected long getTailPointer(int partition) {
        return super.tails.getAcquire(partition);
    }

    protected boolean continueTailCAS() {
        return !super.retired.getAcquire();
    }

    @Override
    protected boolean casTailPointer(int partition, long expect, long update) {
        return super.tails.compareAndSet(partition, expect, update);
    }

    @Override
    protected long getHeadPointer(int partition) {
        return super.heads.getAcquire(partition);
    }

    @Override
    protected void moveHeadPointer(int partition, long delta) {
        super.heads.getAndAdd(partition, delta);
    }

    @Override
    protected long getHeadSequence(int partition) {
        return super.headSequence.getAcquire(partition);
    }

    @Override
    protected boolean casHeadSequence(int partition, long expect, long update) {
        return super.headSequence.compareAndSet(partition, expect, update);
    }
}
