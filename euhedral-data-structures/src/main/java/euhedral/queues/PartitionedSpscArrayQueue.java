package euhedral.queues;

public final class PartitionedSpscArrayQueue<T> extends ConcurrentPartitionedArrayQueue<T> {

    public PartitionedSpscArrayQueue(int partitions, int chunkSize) {
        super(partitions, chunkSize, false, false);
    }

    PartitionedSpscArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        super(partitions, chunkSize, false, unbounded);
    }
}
