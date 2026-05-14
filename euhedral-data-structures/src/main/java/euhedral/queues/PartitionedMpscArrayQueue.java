package euhedral.queues;

public final class PartitionedMpscArrayQueue<T> extends ConcurrentPartitionedArrayQueue<T> {

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
