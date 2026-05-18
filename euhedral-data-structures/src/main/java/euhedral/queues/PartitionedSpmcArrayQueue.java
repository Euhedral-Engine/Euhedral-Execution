package euhedral.queues;

/// ## A partitioned, padded, SPMC array-based queue.
///
/// This class is thread-safe for any drain, poll, or peek method. It is not thread-safe for offer.
/// It is derived from [ConcurrentPartitionedArrayQueue] and overrides the logic for head and tail
/// interaction to make it safe for use as an SPMC.
///
/// ### This class has two operating modes
/// <b>Bounded Mode:</b>
///
/// When a partition is full, offers to that partition will be rejected until space frees.
///
/// <b>Unbounded Mode:</b>
///
/// In unbounded mode, this class is will be managed by another class i.e.
/// [PartitionedUnboundedSpmcArrayQueue]. When a partition is full and an offer is rejected, this
/// queue is marked `retired` and all subsequent offers to any partition are rejected. When the
/// managing class is ready to use the instance again, they will clear the queue to allow offers.
///
/// @param <T> Type to store
public final class PartitionedSpmcArrayQueue<T> extends ConcurrentPartitionedArrayQueue<T> {

    public PartitionedSpmcArrayQueue(int chunkSize) {
        super(1, chunkSize, true, false);
    }

    public PartitionedSpmcArrayQueue(int partitions, int chunkSize) {
        super(partitions, chunkSize, true, false);
    }

    PartitionedSpmcArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        super(partitions, chunkSize, true, unbounded);
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
