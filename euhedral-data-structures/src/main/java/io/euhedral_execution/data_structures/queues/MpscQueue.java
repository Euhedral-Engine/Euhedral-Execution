package io.euhedral_execution.data_structures.queues;

import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.util.Collection;
import java.util.function.Consumer;
import lombok.Getter;

@SuppressWarnings({"unchecked", "unused"})
public sealed class MpscQueue<T> extends BaseConcurrentQueue<T> permits BoundedMpscQueue {

    private final ChunkAllocator allocator;

    @Getter
    private final int maxPooledChunks;

    private final long capacity;

    public MpscQueue(int chunkSize) {
        this(chunkSize, 0);
    }

    public MpscQueue(int chunkSize, int maxPooledChunks) {
        this(chunkSize, maxPooledChunks, false);
    }

    MpscQueue(int chunkSize, int maxPooledChunks, boolean bounded) {
        super(chunkSize, bounded);
        if (maxPooledChunks > 0) {
            this.allocator = new ChunkAllocator(maxPooledChunks, maxPooledChunks);
            this.maxPooledChunks = maxPooledChunks;
        } else {
            this.allocator = null;
            this.maxPooledChunks = 0;
        }
        if(bounded) {
            this.capacity = (QueueUtils.chunkMask(chunkSize) >>> QueueUtils.SHIFT);
        } else {
            this.capacity = Long.MAX_VALUE;
        }
    }

    @Override
    public final boolean offer(T obj) {
        return mpOffer(obj);
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
    public final int fill(T[] objs) {
        return mpFill(objs);
    }

    @Override
    public int fill(T[] objs, int start, int end) {
        return mpFill(objs, start, end);
    }

    @Override
    public final int fill(Collection<T> objs) {
        return mpFill((Collection<Object>) objs);
    }

    @Override
    public final long drain(Consumer<T> consumer, long limit) {
        return scDrain((Consumer<Object>) consumer, limit);
    }

    public final long drain(BaseConcurrentQueue<T> receiver, long limit) {
        return drain(receiver, null, limit);
    }

    public final long drain(BaseConcurrentQueue<T> receiver, Consumer<T> sideEffect, long limit) {
        if(receiver instanceof SpscQueue<T> spsc) {
            return scToSpTransfer(spsc, sideEffect, limit);
        }
        if(receiver instanceof SpmcQueue<T> spmc) {
            return scToSpTransfer(spmc, sideEffect, limit);
        }
        return scToMpTransfer(receiver, sideEffect, limit);
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

    @Override
    public final long capacity() {
        return this.capacity;
    }
}
