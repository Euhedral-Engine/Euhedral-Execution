package io.euhedral_execution.data_structures.queues.common;

import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public interface PartitionedQueue<T> extends Queue<T> {

    /// Offers the object to each partition starting from 0 until it succeeds.
    ///
    /// @return success
    default boolean offer(T obj) {
        return offer(ThreadLocalRandom.current().nextLong(), obj);
    }

    /// Offers the object to a random partition based on the seed. If the seed does not change, the
    /// same partition will be picked.
    ///
    /// @return success
    boolean offer(long randomSeed, T obj);

    /// Offers the object to a specific partition
    ///
    /// @return success
    boolean offer(int partition, T obj);

    T peek(int partition);

    T poll(int partition);

    default long drain(Consumer<T> consumer) {
        return drain(consumer, Long.MAX_VALUE);
    }

    long drain(Consumer<T> consumer, long limit);

    long drain(Consumer<T> consumer, long limit, int startingPartition);

    long drain(int partition, Consumer<T> consumer, long limit);

    boolean isEmpty();

    boolean isEmpty(int partition);

    long sizeLong();

    long size(int partition);

    int maxPooledChunks();

    int partitions();

    long capacity();

    void clear();

    void clear(int partition);
}
