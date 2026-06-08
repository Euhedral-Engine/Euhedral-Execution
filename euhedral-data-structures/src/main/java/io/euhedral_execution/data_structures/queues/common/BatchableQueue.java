package io.euhedral_execution.data_structures.queues.common;

import java.util.Collection;
import java.util.function.Consumer;

public interface BatchableQueue<T> {

    int fill(T[] objs);

    int fill(T[] objs, int start, int end);

    int fill(Collection<T> objs);

    boolean offer(T obj);

    long drain(Consumer<T> consumer, long limit);

    T poll();

    T peek();

    // Performs a `poll()` without waiting to win synchronization
    default T tryPoll() {
        return poll();
    }

    // Performs a `peek()` without waiting to win synchronization
    default T tryPeek() {
        return peek();
    }

    void clear();

    long sizeLong();

    long capacity();

    default boolean isEmpty() {
        return sizeLong() == 0;
    }
}
