package euhedral.queues;

import euhedral.queues.QueueNode.Type;

public final class PartitionedUnboundedSpscArrayQueue<T> extends ConcurrentPartitionedUnboundedArrayQueue<T>{

    public PartitionedUnboundedSpscArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions, chunkSize, maxPooledChunks, Type.SPSC);
    }

    // ----- Head -----

    @Override
    protected QueueNode<T> getHeadNode(int partition) {
        return super.headPointers.getOpaque(partition);
    }

    @Override
    protected QueueNode<T> getNextHeadNode(QueueNode<T> head) {
        return head.next.getOpaque();
    }

    @Override
    protected void setNextHeadNode(int partition, QueueNode<T> next) {
        super.headPointers.setOpaque(partition, next);
    }
}
