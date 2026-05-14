package euhedral.queues;

import static euhedral.queues.common.QueueUtils.LONG_PAD;

import euhedral.atomics.PaddedAtomicReferenceArray;
import euhedral.atomics.PaddedLongAdder;
import euhedral.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.StringJoiner;

abstract sealed class ConcurrentPartitionedArrayQueue<T> extends PartitionedArrayQueue<T>
        permits PartitionedSpscArrayQueue, PartitionedSpmcArrayQueue, PartitionedMpscArrayQueue,
        PartitionedMpmcArrayQueue {

    protected static final VarHandle LA_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);

    protected final int sequenceMask;

    protected final PaddedAtomicReferenceArray<long[]> tailSequence;
    protected final PaddedLongAdder headSequence;
    protected final PaddedLongAdder inFlight;

    public ConcurrentPartitionedArrayQueue(int partitions, int chunkSize, boolean multiConsumer,
            boolean unbounded) {
        super(partitions, chunkSize, unbounded);
        this.tailSequence = new PaddedAtomicReferenceArray<>(partitions, false, true);

        int numSChunks = Math.max(chunkSize >>> 6, 1);
        this.sequenceMask = numSChunks - 1;
        for (int i = 0; i < partitions; i++) {
            int rIdx = this.tailSequence.fromRawIdx(i);
            this.tailSequence.setPlain(rIdx, new long[numSChunks + 2 * LONG_PAD]);
        }

        if (multiConsumer) {
            this.headSequence = new PaddedLongAdder(partitions, false, true);
        } else {
            this.headSequence = null;
        }

        if (unbounded) {
            this.inFlight = new PaddedLongAdder(partitions, false, true);
        } else {
            this.inFlight = null;
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

        if (super.unbounded && super.retired.getAcquire()) {
            return false;
        }

        int pIdx = super.heads.fromRawIdx(partition);
        int rIdx = this.tailSequence.fromRawIdx(partition);
        if (super.unbounded) {
            incrementInFlight(pIdx);
        }

        try {
            long head;
            long tail;
            do {
                if (super.unbounded && !continueTailCAS()) {
                    return false;
                }

                head = super.heads.getAcquire(pIdx);
                tail = getTailPointer(pIdx);
                if (QueueUtils.unsignedDiff(head, tail + 1) > super.chunkSize) {
                    if (super.unbounded) {
                        super.retired.setRelease(true);
                    }
                    return false;
                }
            } while (!casTailPointer(pIdx, tail, tail + 1));

            int qTailIdx = chunkIndex(tail);
            T[] pQueue = super.queue.getPlain(rIdx);
            pQueue[qTailIdx] = obj;

            VarHandle.releaseFence();

            LA_HANDLE.getAndBitwiseOr(this.tailSequence.getPlain(rIdx), sequenceChunkIndex(tail),
                    getSequenceNumber(tail));
            return true;
        } finally {
            if (super.unbounded) {
                decrementInFlight(pIdx);
            }
        }
    }

    @Override
    public T peek(int partition) {
        boundsCheck(partition);
        int pIdx = super.heads.fromRawIdx(partition);
        int rIdx = this.tailSequence.fromRawIdx(partition);
        long head = getHeadPointer(pIdx);

        int sChunkIdx = sequenceChunkIndex(head);
        long sNum = getSequenceNumber(head);
        long sChunk = (long) LA_HANDLE.getAcquire(this.tailSequence.getPlain(rIdx), sChunkIdx);

        if ((sNum & sChunk) == 0) {
            return null;
        }

        T[] pQueue = super.queue.getPlain(rIdx);
        VarHandle.acquireFence();
        return pQueue[chunkIndex(head)];
    }

    @Override
    public T poll(int partition) {
        boundsCheck(partition);
        int pIdx = super.heads.fromRawIdx(partition);
        int rIdx = this.tailSequence.fromRawIdx(partition);

        T[] pQueue = super.queue.getPlain(rIdx);
        while (true) {
            long head = getHeadPointer(pIdx);
            long[] tailSequence = this.tailSequence.getPlain(rIdx);

            int sChunkIdx = sequenceChunkIndex(head);
            long sNum = getSequenceNumber(head);

            long sChunk = (long) LA_HANDLE.getAcquire(tailSequence, sChunkIdx);

            if ((sNum & sChunk) == 0) {
                return null;
            }

            long headSequence = getHeadSequence(pIdx);
            if (head != headSequence || !casHeadSequence(pIdx, headSequence, headSequence + 1)) {
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
        limit = Math.min(super.chunkSize, limit);

        long head;
        long headSequence;
        int total = 0;

        int pIdx = super.heads.fromRawIdx(partition);
        int rIdx = this.tailSequence.fromRawIdx(partition);

        T[] pQueue = super.queue.getPlain(rIdx);
        long[] tailSequence = this.tailSequence.getPlain(rIdx);
        while (total < limit) {
            int reserved;
            int sChunkIdx;
            long clearMask;
            do {
                head = getHeadPointer(pIdx);
                headSequence = getHeadSequence(pIdx);

                if (head != headSequence) {
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
            } while (!casHeadSequence(pIdx, headSequence, headSequence + reserved));

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
        this.inFlight.setOpaque(pIdx, this.inFlight.getOpaque(pIdx) + 1);
    }

    protected void decrementInFlight(int pIdx) {
        this.inFlight.setOpaque(pIdx, this.inFlight.getOpaque(pIdx) - 1);
    }

    protected long getTailPointer(int pIdx) {
        return this.tails.getOpaque(pIdx);
    }

    protected boolean continueTailCAS() {
        return !super.retired.getOpaque();
    }

    protected boolean casTailPointer(int pIdx, long expect, long update) {
        super.tails.setOpaque(pIdx, update);
        return true;
    }

    // Head Movements and Updates

    protected long getHeadPointer(int pIdx) {
        return super.heads.getOpaque(pIdx);
    }

    /// Defaults to moving with a plain read and an opaque set.
    protected void moveHeadPointer(int pIdx, long delta) {
        super.heads.setOpaque(pIdx, super.heads.getOpaque(pIdx) + delta);
    }

    /// Default returns the head
    protected long getHeadSequence(int pIdx) {
        return super.heads.getOpaque(pIdx);
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

    public final boolean isRetired() {
        return super.retired.getAcquire();
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < super.partitions; i++) {
            int pIdx = super.heads.fromRawIdx(i);
            long head = super.heads.getPlain(pIdx);
            long tail = super.tails.getPlain(pIdx);
            long headSeq = this.headSequence == null ? head : this.headSequence.getPlain(pIdx);
            long inFlight = this.inFlight == null ? 0 : this.inFlight.getPlain(pIdx);

            boolean empty = head == tail && head == headSeq && inFlight == 0 && isPartitionEmptyInternal(
                    i);
            if (!empty) {
                return false;
            }
        }
        VarHandle.acquireFence();
        return true;
    }

    @Override
    public boolean isEmpty(int partition) {
        int pIdx = super.heads.fromRawIdx(partition);

        long head = super.heads.getPlain(pIdx);
        long tail = super.tails.getPlain(pIdx);
        long headSeq = this.headSequence == null ? head : this.headSequence.getPlain(pIdx);
        long inFlight = this.inFlight == null ? 0 : this.inFlight.getPlain(pIdx);
        VarHandle.acquireFence();

        return head == tail && head == headSeq && inFlight == 0 && isPartitionEmptyInternal(
                partition);
    }

    protected final boolean isPartitionEmptyInternal(int partition) {
        int rIdx = this.tailSequence.fromRawIdx(partition);
        int logicalIdx = 0;

        int start = sequenceChunkIndex(logicalIdx);
        int curr = start;
        do {
            long chunk = this.tailSequence.getPlain(rIdx)[curr];
            if (chunk != 0) {
                return false;
            }
            curr = sequenceChunkIndex(++logicalIdx);
        } while (curr != start);
        VarHandle.acquireFence();
        return true;
    }

    @Override
    public long size(int partition) {
        int pIdx = this.heads.fromRawIdx(partition);
        long tail = this.tails.getAcquire(pIdx);
        long head = this.heads.getAcquire(pIdx);
        return QueueUtils.unsignedDiff(head, tail);
    }

    @Override
    public void clear() {
        VarHandle.releaseFence();

        for (int i = 0; i < super.partitions; i++) {
            int pIdx = super.heads.fromRawIdx(i);

            super.heads.setPlain(pIdx, 0);
            super.tails.setPlain(pIdx, 0);
            if (this.headSequence != null) {
                this.headSequence.setPlain(pIdx, 0);
            }
            if (this.inFlight != null) {
                this.inFlight.setPlain(pIdx, 0);
            }
            int rIdx = this.tailSequence.fromRawIdx(pIdx);
            var tS = this.tailSequence.getPlain(rIdx);
            Arrays.fill(tS, 0);
        }
        VarHandle.acquireFence();
        this.retired.lazySet(false);
    }

    public String getState() {

        StringJoiner sj = new StringJoiner("\n");
        sj.add(this.getClass().getSimpleName());
        sj.add("Retired: " + super.retired.getAcquire());
        sj.add("Heads: " + super.heads);
        sj.add("Tails: " + super.tails);
        if (this.inFlight != null) {
            sj.add("InFlight: " + this.inFlight);
        }
        if (this.headSequence != null) {
            sj.add("HeadSequence: " + this.headSequence);
        }
        for (int i = 0; i < super.partitions; i++) {
            int rIdx = this.tailSequence.fromRawIdx(i);
            sj.add("TailSequence-p" + i + ": " + Arrays.toString(this.tailSequence.getPlain(rIdx)));
        }

        VarHandle.acquireFence();
        return sj.toString();
    }

    @Override
    public String toString() {
        return getState();
    }
}
