package euhedral.queues;

import euhedral.queues.QueueNode.Type;

public final class PartitionedUnboundedSpmcArrayQueue<T> extends ConcurrentPartitionedUnboundedArrayQueue<T> {

    public PartitionedUnboundedSpmcArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions, chunkSize, maxPooledChunks, Type.SPMC);
    }

    // ----- Head -----

    @Override
    protected QueueNode<T> getHeadNode(int partition) {
        return super.headQueues.peek(partition);
    }

    @Override
    protected QueueNode<T> getNextHeadNode(QueueNode<T> head) {
        return head.next.getAcquire();
    }

    @Override
    protected void setNextHeadNode(int partition, QueueNode<T> next) {
        super.headQueues.poll(partition);
        while (!super.headQueues.offer(partition, next)) {
            Thread.onSpinWait();
        }
    }
}
