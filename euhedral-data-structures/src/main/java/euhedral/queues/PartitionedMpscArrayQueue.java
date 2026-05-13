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
}
