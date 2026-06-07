package io.euhedral_execution.data_structures.queues;

@SuppressWarnings("unused")
public final class BoundedMpmcQueue<T> extends MpmcQueue<T> {

    public BoundedMpmcQueue(int capacity) {
        this(capacity, Long.MAX_VALUE);
    }

    public BoundedMpmcQueue(int capacity, long maxConsumeBatch) {
        super(capacity, 0, maxConsumeBatch, true);
    }
}
