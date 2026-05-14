package euhedral.queues;

/// ## A partitioned, padded, MPMC array-based queue
///
/// This class is thread-safe for all methods. It is derived from [ConcurrentPartitionedArrayQueue]
/// and overrides the logic for head and tail interaction to make it safe for use as an MPMC.
///
/// ### This class has two operating modes
/// <b>Bounded Mode:</b>
///
/// When a partition is full, offers to that partition will be rejected until space frees.
///
/// <b>Unbounded Mode:</b>
///
/// In unbounded mode, this class is will be managed by another class i.e.
/// [PartitionedUnboundedMpmcArrayQueue]. When a partition is full and an offer is rejected, this
/// queue is marked `retired` and all subsequent offers to any partition are rejected. When the
/// managing class is ready to use the instance again, they will clear the queue to allow offers.
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
    protected void incrementInFlight(int pIdx) {
        super.inFlight.getAndIncrement(pIdx);
    }

    @Override
    protected void decrementInFlight(int pIdx) {
        super.inFlight.getAndDecrement(pIdx);
    }

    @Override
    protected long getTailPointer(int pIdx) {
        return super.tails.getAcquire(pIdx);
    }

    protected boolean continueTailCAS() {
        return !super.retired.getAcquire();
    }

    @Override
    protected boolean casTailPointer(int pIdx, long expect, long update) {
        return super.tails.compareAndSet(pIdx, expect, update);
    }

    @Override
    protected long getHeadPointer(int pIdx) {
        return super.heads.getAcquire(pIdx);
    }

    @Override
    protected void moveHeadPointer(int pIdx, long delta) {
        super.heads.getAndAdd(pIdx, delta);
    }

    @Override
    protected long getHeadSequence(int pIdx) {
        return super.headSequence.getAcquire(pIdx);
    }

    @Override
    protected boolean casHeadSequence(int pIdx, long expect, long update) {
        return super.headSequence.compareAndSet(pIdx, expect, update);
    }
}
