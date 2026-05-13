package euhedral.queues;

public final class PartitionedSpmcArrayQueue<T> extends ConcurrentPartitionedArrayQueue<T> {

    public PartitionedSpmcArrayQueue(int partitions, int chunkSize) {
        super(partitions, chunkSize, true, false);
    }

    PartitionedSpmcArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        super(partitions, chunkSize, true, unbounded);
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
    protected boolean casHeadSequence(int pIdx, long headSequence, long reserved) {
        return LA_HANDLE.compareAndSet(super.headSequence, pIdx, headSequence, reserved);
    }
}
