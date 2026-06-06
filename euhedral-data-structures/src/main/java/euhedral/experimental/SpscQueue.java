package euhedral.experimental;

import euhedral.queues.common.QueueUtils;
import java.util.function.Consumer;

@SuppressWarnings({"unchecked", "unused"})
public final class SpscQueue<T> extends BaseConcurrentQueue<T> {

    private final ChunkAllocator allocator;

    public SpscQueue(int chunkSize) {
        this(chunkSize, 0);
    }

    public SpscQueue(int chunkSize, int maxPooledChunks) {
        super(chunkSize);

        if (maxPooledChunks > 0) {
            this.allocator = new ChunkAllocator(maxPooledChunks, maxPooledChunks);
        } else {
            this.allocator = null;
        }
    }

    @Override
    public boolean offer(T obj) {
        spOffer(obj);
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
        spFill(objs);
    }

    @Override
    public void fill(Iterable<T> objs) {
        spFill((Iterable<Object>) objs);
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
