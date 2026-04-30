package euhedral.queues;

@FunctionalInterface
public interface QueueConsumer<T> {
    void consume(T obj);
}
