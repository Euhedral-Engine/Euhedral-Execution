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
        return super.heads.getAcquire(pIdx);
    }

    @Override
    protected void moveHeadPointer(int pIdx, long delta) {
        super.heads.getAndAdd(pIdx, delta);
    }

    @Override
    protected long getHeadSequence(int partition) {
        return super.headSequence.getAcquire(partition);
    }

    @Override
    protected boolean casHeadSequence(int partition, long expect, long update) {
        return super.headSequence.compareAndSet(partition, expect, update);
    }
}
