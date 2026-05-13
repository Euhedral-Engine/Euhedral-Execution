package euhedral.queues;

import euhedral.queues.QueueNode.Type;

/// An unbounded multi-producer-multi-consumer array queue with partitions. This class is
/// thread-safe for any method. It is derived from [PartitionedUnboundedArrayQueue] but overrides
/// the logic for head and tail interaction to make it safe for use as an MPMC.
///
/// @param <T> Type to store
public final class PartitionedUnboundedMpmcArrayQueue<T> extends PartitionedUnboundedArrayQueue<T> {

    public PartitionedUnboundedMpmcArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions, chunkSize, maxPooledChunks, Type.MPMC);
    }
}
