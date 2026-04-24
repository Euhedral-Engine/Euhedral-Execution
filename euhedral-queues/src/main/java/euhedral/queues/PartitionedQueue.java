package euhedral.queues;

public interface PartitionedQueue<T> {

    boolean offer(long randomSeed, T obj);

    boolean offer(int partition, T obj);

    int drain(T[] buffer, int offset, int limit);

    int drain(int partition, T[] buffer, int offset, int limit);
}
