package euhedral.queues.common;

import java.util.Queue;
import java.util.function.Consumer;

public interface PartitionedQueue<T> extends Queue<T> {

    /// Offers the object to each partition starting from 0 until it succeeds.
    ///
    /// @return success
    boolean offer(T element);

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

    long drain(Consumer<T> consumer, long limit);

    long drain(int partition, Consumer<T> consumer, long limit);

    boolean isEmpty();

    boolean isEmpty(int partition);

    long sizeLong();

    long size(int partition);

    default int maxPooledChunks() {
        return 0;
    }

    int partitions();

    long capacity();

    void clear();

    void clear(int partition);
}
