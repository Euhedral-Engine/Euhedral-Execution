package io.euhedral_execution.data_structures.queues;

import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;

@SuppressWarnings({"unchecked", "unused", "rawtypes"})
final class ChunkAllocator extends BaseConcurrentQueue {

    private static final VarHandle STORED;

    static {
        try {
            STORED = MethodHandles.lookup().findVarHandle(ChunkAllocator.class, "stored", int.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int maxPooledChunks;

    private int stored = 0;

    public ChunkAllocator(int chunkSize, int maxPooledChunks) {
        super(Math.max(chunkSize, 4), true);
        this.maxPooledChunks = maxPooledChunks;
    }

    public Object[] allocate(int chunkSize) {
        Object[] chunk = (Object[]) scPoll();
        if (chunk == null) {
            return new Object[chunkSize];
        }
        STORED.getAndAddRelease(this, -1);
        return chunk;
    }

    public void free(Object[] chunk) {
        int stored = (int) STORED.getAcquire(this);
        if (stored >= maxPooledChunks) {
            return;
        }
        STORED.getAndAddRelease(this, 1);

        chunk[chunk.length - 1] = null;
        spOffer(chunk);
    }

    @Override
    public boolean offer(Object obj) {
        return false;
    }

    @Override
    public Object peek() {
        return null;
    }

    @Override
    public Object poll() {
        return null;
    }

    @Override
    public int fill(Object[] objs) {
        return 0;
    }

    @Override
    public int fill(Object[] objs, int start, int end) {
        return 0;
    }

    @Override
    public int fill(Collection objs) {
        return 0;
    }

    @Override
    public long drain(Consumer consumer, long limit) {
        return 0;
    }

    @Override
    public long drain(Consumer consumer, Function stopCondition, long limit) {
        return 0;
    }

    @Override
    public void clear() {
        while (scDrain(QueueUtils.NO_OP, Long.MAX_VALUE) > 0) {
            Thread.onSpinWait();
        }
    }

    @Override
    public long capacity() {
        return this.maxPooledChunks;
    }
}
