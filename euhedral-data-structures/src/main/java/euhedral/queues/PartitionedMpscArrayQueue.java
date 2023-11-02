package euhedral.queues;

/// ## A partitioned, padded, MPSC array-based queue
///
/// This class is thread-safe for any offer method. It is not thread-safe for peek, poll, or drain.
/// It is derived from [ConcurrentPartitionedArrayQueue] and overrides the logic for head and tail
/// interaction to make it safe for use as an MPSC.
///
/// ### This class has two operating modes
/// <b>Bounded Mode:</b>
///
/// When a partition is full, offers to that partition will be rejected until space frees.
///
/// <b>Unbounded Mode:</b>
///
/// In unbounded mode, this class is will be managed by another class i.e.
/// [PartitionedUnboundedMpscArrayQueue]. When a partition is full and an offer is rejected, this
/// queue is marked `retired` and all subsequent offers to any partition are rejected. When the
/// managing class is ready to use the instance again, they will clear the queue to allow offers.
///
/// @param <T> Type to store
public final class PartitionedMpscArrayQueue<T> extends ConcurrentPartitionedArrayQueue<T> {

    public PartitionedMpscArrayQueue(int chunkSize) {
        super(1, chunkSize, false, false);
    }

    public PartitionedMpscArrayQueue(int partitions, int chunkSize) {
        super(partitions, chunkSize, false, false);
    }

    PartitionedMpscArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        super(partitions, chunkSize, false, unbounded);
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

    @Override
    protected boolean continueTailCAS() {
        return !super.retired.getAcquire();
    }

    @Override
    protected boolean casTailPointer(int pIdx, long expect, long update) {
        return super.tails.compareAndSet(pIdx, expect, update);
    }
}
