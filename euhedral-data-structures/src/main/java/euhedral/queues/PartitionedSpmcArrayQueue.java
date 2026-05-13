package euhedral.queues;

public final class PartitionedSpmcArrayQueue<T> extends ConcurrentPartitionedArrayQueue<T> {

    public PartitionedSpmcArrayQueue(int partitions, int chunkSize) {
        super(partitions, chunkSize, true, false);
    }

    PartitionedSpmcArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        super(partitions, chunkSize, true, unbounded);
    }

    @Override
    protected long getHeadPointer(int partition) {
        return super.heads.getAcquire(partition);
    }

    @Override
    protected void moveHeadPointer(int partition, long delta) {
        super.heads.getAndAdd(partition, delta);
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
