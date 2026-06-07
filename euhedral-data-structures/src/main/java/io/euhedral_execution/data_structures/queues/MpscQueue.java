package io.euhedral_execution.data_structures.queues;

import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.util.function.Consumer;

@SuppressWarnings({"unchecked", "unused"})
public final class MpscQueue<T> extends BaseConcurrentQueue<T> {

    private final ChunkAllocator allocator;

    public MpscQueue(int chunkSize) {
        this(chunkSize, 0);
    }

    public MpscQueue(int chunkSize, int maxPooledChunks) {
        super(chunkSize);

        if (maxPooledChunks > 0) {
            this.allocator = new ChunkAllocator(maxPooledChunks, maxPooledChunks);
        } else {
            this.allocator = null;
        }
    }

    @Override
    public boolean offer(T obj) {
        mpOffer(obj);
        return true;
    }

    @Override
    public T peek() {
        return (T) scPeek();
    }

    @Override
    public T poll() {
        return (T) scPoll();
    }

    @Override
    public void fill(T[] objs) {
        mpFill(objs);
    }

    @Override
    public void fill(Iterable<T> objs) {
        mpFill((Iterable<Object>) objs);
    }

    @Override
    public long drain(Consumer<T> consumer, long limit) {
        return scDrain((Consumer<Object>) consumer, limit);
    }

    @Override
    public void clear() {
        while (scDrain(QueueUtils.NO_OP, Long.MAX_VALUE) > 0) {
            Thread.onSpinWait();
        }
    }

    @Override
    protected Object[] allocateChunk(int chunkSize) {
        if (this.allocator == null) {
            return new Object[chunkSize];
        }
        return this.allocator.allocate(chunkSize);
    }

    @Override
    protected void freeChunk(Object[] chunk) {
        if (this.allocator != null) {
            this.allocator.free(chunk);
        }
    }
}
