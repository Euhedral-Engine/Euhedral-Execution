package io.euhedral_execution.data_structures.queues;

import java.util.function.Consumer;
import java.util.function.Function;

/// ## An unbounded MPMC array queue with partitions.
///
/// @param <T> Type to store
@SuppressWarnings({"unchecked", "unused"})
public final class PartitionedMpmcQueue<T> extends AbstractPartitionedQueue<T> {

    private final MpmcQueue<T>[] queues;

    public PartitionedMpmcQueue(int chunkSize) {
        this(1, chunkSize, 0);
    }

    public PartitionedMpmcQueue(int partitions, int chunkSize) {
        this(partitions, chunkSize, 0);
    }

    public PartitionedMpmcQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions);
        this.queues = new MpmcQueue[partitions];
        for (int i = 0; i < partitions; i++) {
            this.queues[i] = new MpmcQueue<>(chunkSize, maxPooledChunks);
        }
    }

    @Override
    public boolean offer(int partition, T obj) {
        return this.queues[partition].offer(obj);
    }

    @Override
    public T peek(int partition) {
        return this.queues[partition].peek();
    }

    @Override
    public T poll(int partition) {
        return this.queues[partition].poll();
    }

    @Override
    public long drain(int partition, Consumer<T> consumer, long limit) {
        return this.queues[partition].drain(consumer, limit);
    }

    @Override
    public long drain(int partition, Consumer<T> consumer, Function<T, Boolean> stopCondition, long limit) {
        return this.queues[partition].drain(consumer, stopCondition, limit);
    }

    @Override
    public boolean isEmpty(int partition) {
        return this.queues[partition].isEmpty();
    }

    @Override
    public long size(int partition) {
        return this.queues[partition].size();
    }

    @Override
    public long capacity() {
        return Long.MAX_VALUE;
    }

    @Override
    public void clear(int partition) {
        this.queues[partition].clear();
    }

    @Override
    public int maxPooledChunks() {
        return this.queues[0].getMaxPooledChunks();
    }
}
