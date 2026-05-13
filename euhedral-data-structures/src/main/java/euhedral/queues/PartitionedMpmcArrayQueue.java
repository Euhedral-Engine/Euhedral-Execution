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
    protected void incrementInFlight(int pIdx) {
        LA_HANDLE.getAndAdd(this.inFlight, pIdx, 1);
    }

    @Override
    protected void decrementInFlight(int pIdx) {
        LA_HANDLE.getAndAdd(this.inFlight, pIdx, -1);
    }

    @Override
    protected long getTailPointer(int pIdx) {
        return (long) LA_HANDLE.getAcquire(this.tails, pIdx);
    }

    protected boolean continueTailCAS(int pIdx) {
        return !super.retired.getAcquire();
    }

    @Override
    protected boolean casTailPointer(int pIdx, long expect, long update) {
        return LA_HANDLE.compareAndSet(this.tails, pIdx, expect, update);
    }

    @Override
    protected long getHeadPointer(int pIdx) {
        return (long) LA_HANDLE.getAcquire(this.heads, pIdx);
    }

    @Override
    protected void moveHeadPointer(int pIdx, long delta) {
        LA_HANDLE.getAndAdd(this.heads, pIdx, delta);
    }

    @Override
    protected long getHeadSequence(int pIdx) {
        return (long) LA_HANDLE.getAcquire(heads, pIdx);
    }

    @Override
    protected boolean casHeadSequence(int pIdx, long expect, long update) {
        return LA_HANDLE.compareAndSet(super.headSequence, pIdx, expect, update);
    }
}
