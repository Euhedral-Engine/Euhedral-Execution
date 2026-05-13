package euhedral.queues;

import euhedral.queues.QueueNode.Type;

/// An unbounded multi-producer-single-consumer array queue with partitions. This class is
/// thread-safe for any offer method. It is not thread-safe for peek, poll, or drain. It is derived
/// from [PartitionedUnboundedArrayQueue] but overrides the logic for head and tail interaction to
/// make it safe for use as an MPSC.
///
/// @param <T> Type to store
public final class PartitionedUnboundedMpscArrayQueue<T> extends PartitionedUnboundedArrayQueue<T> {

    public PartitionedUnboundedMpscArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions, chunkSize, maxPooledChunks, Type.MPSC);
    }

}
