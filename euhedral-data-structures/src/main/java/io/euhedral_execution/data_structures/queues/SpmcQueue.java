package io.euhedral_execution.data_structures.queues;

import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.Getter;

@SuppressWarnings({"unchecked", "unused"})
public sealed class SpmcQueue<T> extends BaseConcurrentQueue.MultiConsumer<T> permits
        BoundedSpmcQueue {

    private final ChunkAllocator allocator;
    @Getter
    private final long maxConsumeBatch;

    @Getter
    private final int maxPooledChunks;

    private final long capacity;

    public SpmcQueue(int chunkSize) {
        this(chunkSize, 0, Long.MAX_VALUE);
    }

    public SpmcQueue(int chunkSize, int maxPooledChunks) {
        this(chunkSize, 0, Long.MAX_VALUE);
    }

    public SpmcQueue(int chunkSize, int maxPooledChunks, long maxConsumeBatch) {
        this(chunkSize, maxPooledChunks, maxConsumeBatch, false);
    }

    SpmcQueue(int chunkSize, int maxPooledChunks, long maxConsumeBatch, boolean bounded) {
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
        return spOffer(obj);
    }

    @Override
    public final T peek() {
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
        return spFill(objs);
    }

    @Override
    public int fill(T[] objs, int start, int end) {
        return spFill(objs, start, end);
    }

    @Override
    public final int fill(Collection<T> objs) {
        return spFill((Collection<Object>) objs);
    }

    @Override
    public final long drain(Consumer<T> consumer, long limit) {
        Objects.requireNonNull(consumer);
        if (limit <= 0) {
            return 0;
        }

        long total = 0;
        while (total < limit) {
            if(!acquireMcLock(this)) {
                break;
            }
            try {
                long batch = Math.min(limit - total, this.maxConsumeBatch);
                long temp = scDrain((Consumer<Object>) consumer, batch);
                if (temp == 0) {
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
