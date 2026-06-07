package io.euhedral_execution.data_structures.queues;

@SuppressWarnings("unused")
public final class BoundedMpscQueue<T> extends MpscQueue<T> {

    public BoundedMpscQueue(int capacity) {
        super(capacity, 0, true);
    }
}
