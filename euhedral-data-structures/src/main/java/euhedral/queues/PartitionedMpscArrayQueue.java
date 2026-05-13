package euhedral.queues;

public final class PartitionedMpscArrayQueue<T> extends ConcurrentPartitionedArrayQueue<T> {

    public PartitionedMpscArrayQueue(int partitions, int chunkSize) {
        super(partitions, chunkSize, false, false);
    }

    PartitionedMpscArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        super(partitions, chunkSize, false, unbounded);
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

    @Override
    protected boolean continueTailCAS() {
        return !super.retired.getAcquire();
    }

    @Override
    protected boolean casTailPointer(int partition, long expect, long update) {
        return super.tails.compareAndSet(partition, expect, update);
    }
}
