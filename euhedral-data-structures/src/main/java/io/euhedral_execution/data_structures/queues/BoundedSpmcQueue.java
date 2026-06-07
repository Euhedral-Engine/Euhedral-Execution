package io.euhedral_execution.data_structures.queues;

@SuppressWarnings("unused")
public final class BoundedSpmcQueue<T> extends SpmcQueue<T> {
    public BoundedSpmcQueue(int capacity) {
        this(capacity, Long.MAX_VALUE);
    }

    public BoundedSpmcQueue(int capacity, long maxConsumeBatch) {
        super(capacity, 0, maxConsumeBatch, true);
    }
}
