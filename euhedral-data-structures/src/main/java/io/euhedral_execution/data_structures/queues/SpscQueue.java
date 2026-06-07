package io.euhedral_execution.data_structures.queues;

import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.util.function.Consumer;

@SuppressWarnings({"unchecked", "unused"})
public sealed class SpscQueue<T> extends BaseConcurrentQueue<T> permits BoundedSpscQueue {

    private final ChunkAllocator allocator;

    public SpscQueue(int chunkSize) {
        this(chunkSize, 0);
    }

    public SpscQueue(int chunkSize, int maxPooledChunks) {
        this(chunkSize, maxPooledChunks, false);
    }

    SpscQueue(int chunkSize, int maxPooledChunks, boolean bounded) {
        super(chunkSize, bounded);
        if (maxPooledChunks > 0) {
            this.allocator = new ChunkAllocator(maxPooledChunks, maxPooledChunks);
        } else {
            this.allocator = null;
        }
    }

    @Override
    public final boolean offer(T obj) {
        return spOffer(obj);
    }

    @Override
    public final T peek() {
        return (T) scPeek();
    }

    @Override
    public final T poll() {
        return (T) scPoll();
    }

    @Override
    public final void fill(T[] objs) {
        spFill(objs);
    }

    @Override
    public final void fill(Iterable<T> objs) {
        spFill((Iterable<Object>) objs);
    }

    @Override
    public final long drain(Consumer<T> consumer, long limit) {
        return scDrain((Consumer<Object>) consumer, limit);
    }

    @Override
    public final void clear() {
        while (scDrain(QueueUtils.NO_OP, Long.MAX_VALUE) > 0) {
            Thread.onSpinWait();
        }
    }

    @Override
    protected final Object[] allocateChunk(int chunkSize) {
        if (this.allocator == null) {
            return new Object[chunkSize];
        }
        return this.allocator.allocate(chunkSize);
    }

    @Override
    protected final void freeChunk(Object[] chunk) {
        if (this.allocator != null) {
            this.allocator.free(chunk);
        }
    }
}
