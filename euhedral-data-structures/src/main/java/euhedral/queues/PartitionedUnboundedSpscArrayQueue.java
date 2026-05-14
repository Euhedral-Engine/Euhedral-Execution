package euhedral.queues;

import euhedral.queues.QueueNode.Type;

public final class PartitionedUnboundedSpscArrayQueue<T> extends ConcurrentPartitionedUnboundedArrayQueue<T>{

    public PartitionedUnboundedSpscArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions, chunkSize, maxPooledChunks, Type.SPSC);
    }

    // ----- Head -----

    @Override
    protected QueueNode<T> getHeadNode(int hpIdx) {
        return super.headPointers.getOpaque(hpIdx);
    }

    @Override
    protected QueueNode<T> getNextHeadNode(QueueNode<T> head) {
        return head.next.getOpaque();
    }

    @Override
    protected void setNextHeadNode(int hpIdx, QueueNode<T> next) {
        super.headPointers.setOpaque(hpIdx, next);
    }
}
