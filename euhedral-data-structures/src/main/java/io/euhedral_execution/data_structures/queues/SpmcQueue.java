package io.euhedral_execution.data_structures.queues;

import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.util.Objects;
import java.util.function.Consumer;

@SuppressWarnings({"unchecked", "unused"})
public final class SpmcQueue<T> extends BaseConcurrentQueue.MultiConsumer<T> {

    private final ChunkAllocator allocator;
    private final long maxConsumeBatch;

    public SpmcQueue(int chunkSize) {
        this(chunkSize, 0, Long.MAX_VALUE);
    }

    public SpmcQueue(int chunkSize, int maxPooledChunks) {
        this(chunkSize, 0, Long.MAX_VALUE);
    }

    public SpmcQueue(int chunkSize, int maxPooledChunks, long maxConsumeBatch) {
        super(chunkSize);
        if(maxConsumeBatch <= 0) {
            throw new IllegalArgumentException("maxConsumeBatch must be positive");
        }

        if (maxPooledChunks > 0) {
            this.allocator = new ChunkAllocator(maxPooledChunks, maxPooledChunks);
        } else {
            this.allocator = null;
        }
        this.maxConsumeBatch = maxConsumeBatch;
    }

    @Override
    public boolean offer(T obj) {
        spOffer(obj);
        return true;
    }

    @Override
    public T peek() {
        if (!acquireMcLock(this)) {
            return null;
        }
        try {
            return (T) scPeek();
        } finally {
            releaseMcLock(this);
        }
    }

    @Override
    public T poll() {
        if (!acquireMcLock(this)) {
            return null;
        }
        try {
            return (T) scPoll();
        } finally {
            releaseMcLock(this);
        }
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
        Objects.requireNonNull(consumer);
        if(limit <= 0) {
            return 0;
        }

        long total = 0;
        while(total < limit) {
            if (!acquireMcLock(this)) {
                return total;
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
    public void clear() {
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
