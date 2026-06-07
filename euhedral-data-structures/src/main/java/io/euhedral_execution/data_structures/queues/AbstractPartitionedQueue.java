package io.euhedral_execution.data_structures.queues;

import io.euhedral_execution.data_structures.queues.common.ConcurrentPartitionedQueue;
import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

abstract sealed class AbstractPartitionedQueue<T> extends AbstractQueue<T> implements
        ConcurrentPartitionedQueue<T> permits PartitionedMpmcQueue, PartitionedMpscQueue,
        PartitionedSpmcQueue, PartitionedSpscQueue {

    protected final int partitions;

    AbstractPartitionedQueue(int partitions) {
        this.partitions = partitions;
    }

    @Override
    public final boolean offer(T obj) {
        return offer(ThreadLocalRandom.current().nextLong(), obj);
    }

    @Override
    public final boolean offer(long randomSeed, T obj) {
        int idx = (int) QueueUtils.unsignedMultiplyHigh(randomSeed, partitions);
        return offer(idx, obj);
    }

    @Override
    public final T peek() {
        for (int i = 0; i < partitions; i++) {
            T obj = peek(i);
            if (obj != null) {
                return obj;
            }
        }
        return null;
    }

    @Override
    public final T poll() {
        for (int i = 0; i < partitions; i++) {
            T obj = poll(i);
            if (obj != null) {
                return obj;
            }
        }
        return null;
    }

    @Override
    public final long drain(Consumer<T> consumer, long limit) {
        long total = 0;
        for (int i = 0; i < partitions; i++) {
            total += drain(i, consumer, limit - total);
            if (total == limit) {
                break;
            }
        }
        return total;
    }

    @Override
    public final void clear() {
        for (int i = 0; i < partitions; i++) {
            clear(i);
        }
    }

    @Override
    public final boolean isEmpty() {
        for (int i = 0; i < partitions; i++) {
            if (!isEmpty(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public final long sizeLong() {
        long total = 0;
        for (int i = 0; i < partitions; i++) {
            total += size(i);
            if (total < 0) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    @Override
    public final int partitions() {
        return this.partitions;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "-" + this.getClass().hashCode();
    }

    // ----- Queue<T> Interface -----

    /// Inserts the specified object into this queue if it is possible to do so immediately without
    /// violating capacity restrictions, returning `true` upon success and throwing an
    /// `IllegalStateException` if no space is currently available.
    ///
    /// @param obj the object to add
    /// @return `true` (as specified by [Collection#add])
    /// @throws IllegalStateException if the object cannot be added at this time due to capacity
    /// restrictions
    @Override
    public final boolean add(T obj) {
        if (offer(obj)) {
            return true;
        }
        throw new IllegalStateException("Queue full");
    }

    /// Not supported
    ///
    /// @throws UnsupportedOperationException Not supported
    @Override
    public final @NonNull Iterator<T> iterator() {
        throw new UnsupportedOperationException("iterator not supported");
    }

    /// Use `sizeLong()` for an accurate count
    @Override
    @Deprecated
    public final int size() {
        return (int) sizeLong();
    }
}
