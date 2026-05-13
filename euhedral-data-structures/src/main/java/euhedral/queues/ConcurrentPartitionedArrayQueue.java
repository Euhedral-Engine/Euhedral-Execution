package euhedral.queues;

import static euhedral.queues.common.QueueUtils.ABS_MASK;
import static euhedral.queues.common.QueueUtils.LONG_PAD;

import euhedral.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.StringJoiner;

abstract class ConcurrentPartitionedArrayQueue<T> extends PartitionedArrayQueue<T> {
    protected static final VarHandle LA_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);

    protected final long[][] tailSequence;
    protected final int sequenceMask;

    protected final long[] headSequence;
    protected final long[] inFlight;

    public ConcurrentPartitionedArrayQueue(int partitions, int chunkSize, boolean multiConsumer, boolean unbounded) {
        super(partitions, chunkSize, unbounded);
        this.tailSequence = new long[(partitions + 1) * LONG_PAD + partitions][0];

        int numSChunks = Math.max(chunkSize >>> 6, 1);
        this.sequenceMask = numSChunks - 1;
        for (int i = 0; i < partitions; i++) {
            int pIdx = partitionIndex(i);
            this.tailSequence[pIdx] = new long[numSChunks + 2 * LONG_PAD];
        }

        if(multiConsumer) {
            this.headSequence = new long[(partitions + 1) * LONG_PAD + partitions];
        } else {
            this.headSequence = null;
        }

        if (unbounded) {
            inFlight = new long[(partitions + 1) * LONG_PAD + partitions];
        } else {
            inFlight = null;
        }
    }

    /// Offers an item to a partition.
    ///
    /// @param partition The logical index of the partition. e.g. 16 partitions = (0-15)
    /// @param obj       Item to add
    @Override
    public boolean offer(int partition, T obj) {
        boundsCheck(partition);
        if (obj == null) {
            throw new NullPointerException();
        }

        if (this.unbounded && this.retired.getAcquire()) {
            return false;
        }
        int pIdx = partitionIndex(partition);

        if(this.unbounded) {
            incrementInFlight(pIdx);
        }

        try {
            long head;
            long tail;
            do {
                if (this.unbounded && !continueTailCAS(pIdx)) {
                    return false;
                }

                head = (long) LA_HANDLE.getAcquire(this.heads, pIdx);
                tail = getTailPointer(pIdx);
                if (QueueUtils.unsignedDiff(head, tail + 1) > this.chunkSize) {
                    if (this.unbounded) {
                        this.retired.setRelease(true);
                    }
                    return false;
                }
            } while (!casTailPointer(pIdx, tail, tail + 1));

            int qTailIdx = chunkIndex(tail);
            T[] pQueue = this.queue[queueIndex(partition)];
            pQueue[qTailIdx] = obj;

            VarHandle.releaseFence();

            LA_HANDLE.getAndAdd(this.tailSequence[pIdx], sequenceChunkIndex(tail),
                    getSequenceNumber(tail));
            return true;
        } finally {
            if(this.unbounded) {
                decrementInFlight(pIdx);
            }
        }
    }

    @Override
    public T peek(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        T[] pQueue = this.queue[queueIndex(partition)];

        long head = getHeadPointer(pIdx);
        long[] tailSequence = this.tailSequence[pIdx];

        int sChunkIdx = sequenceChunkIndex(head);
        long sNum = getSequenceNumber(head);

        long sChunk = (long) LA_HANDLE.getAcquire(tailSequence, sChunkIdx);

        if ((sNum & sChunk) == 0) {
            return null;
        }

        VarHandle.acquireFence();
        return pQueue[chunkIndex(head)];
    }

    @Override
    public T poll(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        T[] pQueue = this.queue[queueIndex(partition)];

        while (true) {
            long head = getHeadPointer(pIdx);
            long[] tailSequence = this.tailSequence[pIdx];

            int sChunkIdx = sequenceChunkIndex(head);
            long sNum = getSequenceNumber(head);

            long sChunk = (long) LA_HANDLE.getAcquire(tailSequence, sChunkIdx);

            if ((sNum & sChunk) == 0) {
                return null;
            }

            long headSequence = getHeadSequence(pIdx);
            if(head != headSequence || !casHeadSequence(pIdx, headSequence, headSequence + 1)) {
                continue;
            }

            VarHandle.acquireFence();
            T obj = pQueue[chunkIndex(head)];
            LA_HANDLE.getAndBitwiseAnd(tailSequence, sChunkIdx, ~sNum);

            VarHandle.releaseFence();
            moveHeadPointer(pIdx, 1);
            return obj;
        }
    }

    /// Drains the partition into the consumer.
    ///
    /// @param partition The logical index of the partition. e.g. 16 partitions = (0-15)
    /// @param consumer  Consumer to drain items into
    /// @param limit     Max number of items to take
    @Override
    public int drain(int partition, QueueConsumer<T> consumer, int limit) {
        boundsCheck(partition);
        if (consumer == null || limit <= 0) {
            return 0;
        }
        int pIdx = partitionIndex(partition);
        limit = Math.min(this.chunkSize, limit);

        long head;
        long headSequence;
        int total = 0;

        T[] pQueue = this.queue[queueIndex(partition)];
        long[] tailSequence = this.tailSequence[pIdx];
        while(total < limit) {
            int reserved;
            int sChunkIdx;
            long clearMask;
            do {
                head = getHeadPointer(pIdx);
                headSequence = getHeadSequence(pIdx);

                if(head != headSequence) {
                    return total;
                }

                sChunkIdx = sequenceChunkIndex(head);
                long sChunk = (long) LA_HANDLE.getAcquire(tailSequence, sChunkIdx);
                int bitOffset = (int) (head & 63);

                int nextClearBit = QueueUtils.nextClearBit(sChunk, bitOffset);
                int upperLimit = (nextClearBit == -1) ? 64 : nextClearBit;

                reserved = Math.min(64 - bitOffset, limit - total);
                reserved = Math.min(reserved, upperLimit - bitOffset);
                if (reserved == 0) {
                    return total;
                }
                clearMask = QueueUtils.clearMask(bitOffset, bitOffset + reserved);
            } while(!casHeadSequence(pIdx, headSequence, headSequence + reserved));

            VarHandle.acquireFence();
            for (int j = 0; j < reserved; j++) {
                int qIdx = chunkIndex(head + j);
                consumer.consume(pQueue[qIdx]);
                pQueue[qIdx] = null;
            }
            total += reserved;
            LA_HANDLE.getAndBitwiseAnd(tailSequence, sChunkIdx, clearMask);

            VarHandle.releaseFence();
            moveHeadPointer(pIdx, reserved);
        }
        return total;
    }

    // Tail Movements and Updates

    protected void incrementInFlight(int pIdx) {
        LA_HANDLE.setRelease(this.inFlight, pIdx, this.inFlight[pIdx] + 1);
    }

    protected void decrementInFlight(int pIdx) {
        LA_HANDLE.setRelease(this.inFlight, pIdx, this.inFlight[pIdx] - 1);
    }

    protected long getTailPointer(int pIdx) {
        return (long) LA_HANDLE.getOpaque(this.tails, pIdx);
    }

    protected boolean continueTailCAS(int pIdx) {
        return !this.retired.getOpaque();
    }

    protected boolean casTailPointer(int pIdx, long expect, long update) {
        LA_HANDLE.setOpaque(this.tails, pIdx, update);
        return true;
    }

    // Head Movements and Updates

    protected long getHeadPointer(int pIdx) {
        return (long) LA_HANDLE.getOpaque(this.heads, pIdx);
    }

    /// Default moves with an opaque set, and a plain read.
    protected void moveHeadPointer(int pIdx, long delta) {
        LA_HANDLE.setOpaque(this.heads, pIdx, this.heads[pIdx] + delta);
    }

    /// Default returns the head
    protected long getHeadSequence(int pIdx) {
        return (long) LA_HANDLE.getOpaque(heads, pIdx);
    }

    /// Default has no head sequence tracking.
    protected boolean casHeadSequence(int pIdx, long expect, long update) {
        return true;
    }

    // Padded Index Resolvers

    protected final int sequenceChunkIndex(long idx) {
        return (int) ((idx >>> 6) & this.sequenceMask) + LONG_PAD;
    }

    protected final long getSequenceNumber(long idx) {
        return 1L << (idx & 63);
    }

    // State

    public final int getSize(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);

        long head = (long) LA_HANDLE.getOpaque(this.heads, pIdx);
        long tail = (long) LA_HANDLE.getOpaque(this.tails, pIdx);

        return (int) ((tail - head) & ABS_MASK);
    }

    public final boolean isRetired() {
        return this.retired.getAcquire();
    }

    public boolean isEmpty() {
        for (int i = 0; i < this.partitions; i++) {
            if (!isEmpty(i)) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmpty(int partition) {
        int pIdx = partitionIndex(partition);
        long head = (long) LA_HANDLE.getAcquire(this.heads, pIdx);
        long tail = (long) LA_HANDLE.getAcquire(this.tails, pIdx);
        long headSeq = this.headSequence == null ? head : (long) LA_HANDLE.getAcquire(this.headSequence, pIdx);
        long inFlight = this.inFlight == null ? 0 : (long) LA_HANDLE.getAcquire(this.inFlight, pIdx);

        return head == tail && head == headSeq && inFlight == 0 && isPartitionEmptyInternal(pIdx);
    }

    protected final boolean isPartitionEmptyInternal(int pIdx) {
        int logicalIdx = 0;
        int start = sequenceChunkIndex(logicalIdx);
        int curr = start;
        do {
            long chunk = (long) LA_HANDLE.getAcquire(this.tailSequence[pIdx], curr);
            if (chunk != 0) {
                return false;
            }
            curr = sequenceChunkIndex(++logicalIdx);
        } while (curr != start);
        return true;
    }

    @Override
    public void reset() {
        VarHandle.acquireFence();

        Arrays.fill(this.heads, 0);
        Arrays.fill(this.tails, 0);
        if(this.headSequence != null) {
            Arrays.fill(this.headSequence, 0);
        }
        if(this.inFlight != null) {
            Arrays.fill(this.inFlight, 0);
        }
        for (int i = 0; i < this.partitions; i++) {
            int pIdx = partitionIndex(i);
            Arrays.fill(this.queue[queueIndex(i)], null);
            Arrays.fill(this.tailSequence[pIdx], 0);
        }
        VarHandle.releaseFence();
        this.retired.lazySet(false);
    }

    public String getState() {
        VarHandle.acquireFence();

        StringJoiner sj = new StringJoiner("\n");
        sj.add(this.getClass().getSimpleName());
        sj.add("Retired: " + this.retired.getAcquire());
        sj.add("Heads: " + Arrays.toString(this.heads));
        sj.add("Tails: " + Arrays.toString(this.tails));
        if(this.inFlight != null) {
            sj.add("InFlight: " + Arrays.toString(this.inFlight));
        }
        if(this.headSequence != null) {
            sj.add("HeadSequence: " + Arrays.toString(this.headSequence));
        }
        for(int i = 0; i < this.partitions; i++) {
            sj.add("TailSequence-p" + i + ": " + Arrays.toString(this.tailSequence[partitionIndex(i)]));
        }
        return sj.toString();
    }

    @Override
    public String toString() {
        return getState();
    }
}
