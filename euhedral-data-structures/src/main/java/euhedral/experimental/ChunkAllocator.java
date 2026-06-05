package euhedral.experimental;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

@SuppressWarnings("unused")
public final class ChunkAllocator extends BaseConcurrentQueue {

    private static final VarHandle STORED;

    static {
        try {
            STORED = MethodHandles.lookup()
                    .findVarHandle(ChunkAllocator.class, "stored", int.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private final int maxPooledChunks;

    private int stored = 0;

    public ChunkAllocator(int chunkSize, int maxPooledChunks) {
        super(Math.max(chunkSize, 4));
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
}
