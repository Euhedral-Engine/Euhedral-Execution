package euhedral.queues;

public interface PartitionedQueue<T> {

    boolean offer(long randomSeed, T obj);

    boolean offer(int partition, T obj);

    T peek(int partition);

    T poll(int partition);

    int drain(QueueConsumer<T> consumer, int limit);

    int drain(int partition, QueueConsumer<T> consumer, int limit);
}
