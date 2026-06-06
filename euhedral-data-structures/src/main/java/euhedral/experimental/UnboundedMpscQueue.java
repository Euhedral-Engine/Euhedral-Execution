package euhedral.experimental;

import euhedral.queues.common.QueueUtils;
import java.util.function.Consumer;

@SuppressWarnings({"unchecked", "unused"})
public final class UnboundedMpscQueue<T> extends BaseConcurrentQueue {

    private final ChunkAllocator allocator;

    public UnboundedMpscQueue(int chunkSize) {
        this(chunkSize, 0);
    }

    public UnboundedMpscQueue(int chunkSize, int maxPooledChunks) {
        super(chunkSize);

        if (maxPooledChunks > 0) {
            this.allocator = new ChunkAllocator(maxPooledChunks, maxPooledChunks);
        } else {
            this.allocator = null;
        }
    }

    public boolean offer(T obj) {
        mpOffer(obj);
        return true;
    }

    public T peek() {
        return (T) scPeek();
    }

    public T poll() {
        return (T) scPoll();
    }

    public void fill(T[] objs) {
        mpFill(objs);
    }

    public void fill(Iterable<T> objs) {
        mpFill((Iterable<Object>) objs);
    }

    public long drain(Consumer<T> consumer, long limit) {
        return scDrain((Consumer<Object>) consumer, limit);
    }

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
