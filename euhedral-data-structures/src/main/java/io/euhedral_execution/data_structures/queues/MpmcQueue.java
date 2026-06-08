package io.euhedral_execution.data_structures.queues;

import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.Getter;

@SuppressWarnings({"unchecked", "unused"})
public sealed class MpmcQueue<T> extends BaseConcurrentQueue.MultiConsumer<T> permits BoundedMpmcQueue {

    private final ChunkAllocator allocator;

    @Getter
    private final long maxConsumeBatch;

    @Getter
    private final int maxPooledChunks;

    private final long capacity;

    public MpmcQueue(int chunkSize) {
        this(chunkSize, 0, Long.MAX_VALUE);
    }

    public MpmcQueue(int chunkSize, int maxPooledChunks) {
        this(chunkSize, maxPooledChunks, Long.MAX_VALUE);
    }

    public MpmcQueue(int chunkSize, int maxPooledChunks, long maxConsumeBatch) {
        this(chunkSize, maxPooledChunks, maxConsumeBatch, false);
    }

    MpmcQueue(int chunkSize, int maxPooledChunks, long maxConsumeBatch, boolean bounded) {
        super(chunkSize, bounded);
        if (maxConsumeBatch <= 0) {
            throw new IllegalArgumentException("maxConsumeBatch must be positive");
        }

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
        this.maxConsumeBatch = maxConsumeBatch;
    }

    @Override
    public final boolean offer(T obj) {
        return mpOffer(obj);
    }

    @Override
    public final T peek() {
        while (!acquireMcLock(this)) {
            Thread.onSpinWait();
        }
        try {
            return (T) scPeek();
        } finally {
            releaseMcLock(this);
        }
    }

    @Override
    public final T tryPeek() {
        if(!acquireMcLock(this)) {
            return null;
        }
        try {
            return (T) scPeek();
        } finally {
            releaseMcLock(this);
        }
    }

    @Override
    public final T poll() {
        while (!acquireMcLock(this)) {
            Thread.onSpinWait();
        }
        try {
            return (T) scPoll();
        } finally {
            releaseMcLock(this);
        }
    }

    @Override
    public final T tryPoll() {
        if(!acquireMcLock(this)) {
            return null;
        }
        try {
            return (T) scPoll();
        } finally {
            releaseMcLock(this);
        }
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
        Objects.requireNonNull(consumer);
        if (limit <= 0) {
            return 0;
        }

        long total = 0;
        while (total < limit) {
            while (!acquireMcLock(this)) {
                Thread.onSpinWait();
            }
            try {
                long batch = Math.min(limit - total, this.maxConsumeBatch);
                long temp = scDrain((Consumer<Object>) consumer, batch);
                if(temp == 0) {
                    break;
                }
                total += temp;
            } finally {
                releaseMcLock(this);
            }
        }
        return total;
    }

    @Override
    public final void clear() {
        while (!acquireMcLock(this)) {
            Thread.onSpinWait();
        }
        try {
            while (scDrain(QueueUtils.NO_OP, Long.MAX_VALUE) > 0) {
                Thread.onSpinWait();
            }
        } finally {
            releaseMcLock(this);
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
