package io.euhedral_execution.data_structures.queues;

public final class BoundedSpscQueue<T> extends SpscQueue<T> {
    public BoundedSpscQueue(int chunkSize) {
        super(chunkSize, 0, true);
    }
}
