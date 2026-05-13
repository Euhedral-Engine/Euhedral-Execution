package euhedral.queues;

import euhedral.queues.QueueNode.Type;

public final class PartitionedUnboundedSpmcArrayQueue<T> extends PartitionedUnboundedArrayQueue<T> {

    public PartitionedUnboundedSpmcArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions, chunkSize, maxPooledChunks, Type.SPMC);
    }
}
