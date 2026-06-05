package euhedral.experimental;

import euhedral.queues.common.QueueUtils;
import java.util.function.Consumer;

@SuppressWarnings({"unchecked", "unused"})
public final class UnboundedSpscQueue<T> extends BaseConcurrentQueue {

    private final ChunkAllocator allocator;

    public UnboundedSpscQueue(int chunkSize) {
        super(chunkSize);
        this.allocator = null;
    }

    public UnboundedSpscQueue(int chunkSize, int maxPooledChunks) {
        super(chunkSize);

        if(maxPooledChunks > 0) {
            this.allocator = new ChunkAllocator(maxPooledChunks, maxPooledChunks);
        } else {
            this.allocator = null;
        }
    }

    public boolean offer(T obj) {
        spOffer(obj);
        return true;
    }

    public T peek() {
        return (T) scPeek();
    }

    public T poll() {
        return (T) scPoll();
    }

    public void fill(T[] objs) {
        spFill(objs);
    }

    public void fill(Iterable<T> objs) {
        spFill((Iterable<Object>) objs);
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
        if(allocator == null) {
            return new Object[chunkSize];
        }
        return allocator.allocate(chunkSize);
    }

    @Override
    protected void freeChunk(Object[] chunk) {
        if(allocator != null) {
            allocator.free(chunk);
        }
    }
}
