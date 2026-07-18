package io.euhedral_execution.data_structures.queues.common;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;

public interface BatchableQueue<T> {

    int fill(T[] objs);

    int fill(T[] objs, int start, int end);

    int fill(Collection<T> objs);

    boolean offer(T obj);

    default long drain(Consumer<T> consumer) {
        return drain(consumer, Long.MAX_VALUE);
    }

    long drain(Consumer<T> consumer, long limit);

    long drain(Consumer<T> consumer, Function<T, Boolean> stopCondition, long limit);

    T poll();

    T peek();

    void clear();

    long sizeLong();

    long capacity();

    default boolean isEmpty() {
        return sizeLong() == 0;
    }
}
