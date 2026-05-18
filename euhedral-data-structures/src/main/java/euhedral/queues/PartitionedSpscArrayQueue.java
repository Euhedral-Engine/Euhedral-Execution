package euhedral.queues;

/// ## A partitioned, padded, SPSC array-based queue
///
/// It extends the [ConcurrentPartitionedArrayQueue] but overrides none of the logic as it is
/// already safe for use as an SPSC queue.
///
/// ### This class has two operating modes
/// <b>Bounded Mode:</b>
///
/// When a partition is full, offers to that partition will be rejected until space frees.
///
/// <b>Unbounded Mode:</b>
///
/// In unbounded mode, this class is will be managed by another class i.e.
/// [PartitionedUnboundedSpscArrayQueue]. When a partition is full and an offer is rejected, this
/// queue is marked `retired` and all subsequent offers to any partition are rejected. When the
/// managing class is ready to use the instance again, they will clear the queue to allow offers.
///
/// @param <T> Type to store
public final class PartitionedSpscArrayQueue<T> extends ConcurrentPartitionedArrayQueue<T> {

    public PartitionedSpscArrayQueue(int chunkSize) {
        super(1, chunkSize, false, false);
    }

    public PartitionedSpscArrayQueue(int partitions, int chunkSize) {
        super(partitions, chunkSize, false, false);
    }

    PartitionedSpscArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        super(partitions, chunkSize, false, unbounded);
    }
}
