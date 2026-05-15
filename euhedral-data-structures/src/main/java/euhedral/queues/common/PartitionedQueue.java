package euhedral.queues.common;

import euhedral.queues.QueueConsumer;

public interface PartitionedQueue<T> {

    boolean offer(T element);

    boolean offer(long randomSeed, T obj);

    boolean offer(int partition, T obj);

    T peek(int partition);

    T poll(int partition);

    int drain(QueueConsumer<T> consumer, int limit);

    int drain(int partition, QueueConsumer<T> consumer, int limit);

    boolean isEmpty();

    boolean isEmpty(int partition);

    long size();

    long size(int partition);

    int partitions();

    long capacity();

    void clear();
}
