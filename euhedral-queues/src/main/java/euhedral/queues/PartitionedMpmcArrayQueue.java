package euhedral.queues;

import static euhedral.queues.QueueUtils.ABS_MASK;
import static euhedral.queues.QueueUtils.POINTER_PAD_BYTES;
import static euhedral.queues.QueueUtils.LONG_PAD;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

import lombok.Getter;

/// A partitioned, padded, array-based queue. This class has two operating modes, bounded and
/// unbounded.
///
/// <b>Bounded Mode:</b>
///
/// When a partition is full, offers to that partition will be rejected until space
/// frees.
///
/// <b>Unbounded Mode:<b/>
///
/// In unbounded mode, this class is will be managed by another class i.e.
/// [PartitionedUnboundedMpmcArrayQueue]. When a partition is full and an offer is rejected, this
/// queue is marked `retired` and all subsequent offers to any partition are rejected. When the
/// managing class is ready to use the instance again, they will reset the queue to allow offers.
///
/// @param <T> Type to store
public class PartitionedMpmcArrayQueue<T> implements PartitionedQueue<T> {

    private static final VarHandle LA_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);

    private final T[][] queue;
    private final long[][] sequence;
    private final int sequenceMask;

    private final boolean unbounded;
    private final int partitions;
    private final int chunkSize;
    private final int chunkMask;

    private final long[] heads;
    private final long[] tails;
    private final long[] headSequence;
    private final long[] inFlight;

    @Getter
    private volatile boolean retired = false;

    @SuppressWarnings("unchecked")
    public PartitionedMpmcArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        this.unbounded = unbounded;
        this.partitions = partitions;
        this.chunkSize = chunkSize;
        this.chunkMask = chunkSize - 1;
        this.queue = (T[][]) new Object[(partitions + 1) * POINTER_PAD_BYTES + partitions][0];
        this.sequence = new long[(partitions + 1) * LONG_PAD + partitions][0];

        int numSChunks = Math.max(chunkSize >>> 6, 1);
        this.sequenceMask = numSChunks - 1;
        for (int i = 0; i < partitions; i++) {
            int pIdx = partitionIndex(i);
            this.queue[queueIndex(i)] = (T[]) new Object[chunkSize + POINTER_PAD_BYTES * 2];
            this.sequence[pIdx] = new long[numSChunks + 2 * LONG_PAD];
        }

        this.heads = new long[(partitions + 1) * LONG_PAD + partitions];
        this.tails = new long[(partitions + 1) * LONG_PAD + partitions];
        this.headSequence = new long[(partitions + 1) * LONG_PAD + partitions];
        if (unbounded) {
            inFlight = new long[(partitions + 1) * LONG_PAD + partitions];
        } else {
            inFlight = null;
        }
    }

    /// Offers an item to a random partition.
    ///
    /// @param randomSeed Random number to assign a partition from
    /// @param obj        Item to add
    @Override
    public boolean offer(long randomSeed, T obj) {
        int partition = (int) QueueUtils.unsignedMultiplyHigh(randomSeed, this.partitions);
        return offer(partition, obj);
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

        if (unbounded && retired) {
            return false;
        }
        int pIdx = partitionIndex(partition);

        if (unbounded) {
            LA_HANDLE.getAndAdd(inFlight, pIdx, 1);
            if (retired) {
                LA_HANDLE.getAndAdd(inFlight, pIdx, -1);
                return false;
            }
        }

        long head;
        long tail;
        do {
            head = (long) LA_HANDLE.getVolatile(heads, pIdx);
            tail = (long) LA_HANDLE.getVolatile(tails, pIdx);
            if(QueueUtils.unsignedDiff(head, tail + 1) > chunkSize) {
                if(unbounded) {
                    retired = true;
                }
                return false;
            }
        } while(!LA_HANDLE.compareAndSet(tails, pIdx, tail, tail + 1));

        int qTailIdx = chunkIndex(tail);
        T[] pQueue = queue[queueIndex(partition)];
        pQueue[qTailIdx] = obj;

        VarHandle.releaseFence();

        LA_HANDLE.getAndBitwiseOr(sequence[pIdx], sequenceChunkIndex(tail),
                getSequenceNumber(tail));
        if (unbounded) {
            LA_HANDLE.getAndAdd(inFlight, pIdx, -1);
        }
        return true;
    }

    @Override
    public T peek(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        T[] pQueue = queue[queueIndex(partition)];

        long head = (long) LA_HANDLE.getVolatile(heads, pIdx);
        long[] sequence = this.sequence[pIdx];

        int sChunkIdx = sequenceChunkIndex(head);
        long sNum = getSequenceNumber(head);

        long sChunk = (long) LA_HANDLE.getVolatile(sequence, sChunkIdx);

        if ((sNum & sChunk) == 0) {
            return null;
        }
        return pQueue[chunkIndex(head)];
    }

    @Override
    public T poll(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        T[] pQueue = queue[queueIndex(partition)];

        while (true) {
            long head = (long) LA_HANDLE.getVolatile(heads, pIdx);
            long[] tailSequence = this.sequence[pIdx];

            int sChunkIdx = sequenceChunkIndex(head);
            long sNum = getSequenceNumber(head);

            long sChunk = (long) LA_HANDLE.getVolatile(tailSequence, sChunkIdx);

            if ((sNum & sChunk) == 0) {
                return null;
            }

            long headSequence = (long) LA_HANDLE.getVolatile(this.headSequence, pIdx);
            if(head != headSequence || !LA_HANDLE.compareAndSet(this.headSequence, pIdx, headSequence, headSequence + 1)) {
                continue;
            }

            T obj = pQueue[chunkIndex(head)];
            LA_HANDLE.getAndBitwiseAnd(tailSequence, sChunkIdx, ~sNum);
            LA_HANDLE.getAndAdd(heads, pIdx, 1);
            return obj;
        }
    }

    /// Drains from all partitions sequentially starting from 0 into the consumer.
    ///
    /// @param consumer Consumer to drain items into
    /// @param limit    Max number of items to take
    @Override
    public int drain(QueueConsumer<T> consumer, int limit) {
        if (!drainCheck(consumer, limit)) {
            return 0;
        }

        int total = 0;
        for (int i = 0; i < partitions && limit > 0; i++) {
            int count = drain(i, consumer, limit);
            limit -= count;
            total += count;
        }
        return total;
    }

    /// Drains the partition into the consumer.
    ///
    /// @param partition The logical index of the partition. e.g. 16 partitions = (0-15)
    /// @param consumer  Consumer to drain items into
    /// @param limit     Max number of items to take
    @Override
    public int drain(int partition, QueueConsumer<T> consumer, int limit) {
        boundsCheck(partition);
        if (!drainCheck(consumer, limit)) {
            return 0;
        }
        int pIdx = partitionIndex(partition);
        limit = Math.min(chunkSize, limit);

        long head;
        long headSequence;
        int total = 0;

        T[] pQueue = queue[queueIndex(partition)];
        long[] sequence = this.sequence[pIdx];
        while(total < limit) {
            int reserved;
            int sChunkIdx;
            long clearMask;
            do {
                head = (long) LA_HANDLE.getVolatile(heads, pIdx);
                headSequence = (long) LA_HANDLE.getVolatile(this.headSequence, pIdx);

                if(head != headSequence) {
                    return total;
                }

                sChunkIdx = sequenceChunkIndex(head);
                long sChunk = (long) LA_HANDLE.getVolatile(sequence, sChunkIdx);
                int bitOffset = (int) (head & 63);

                int nextClearBit = QueueUtils.nextClearBit(sChunk, bitOffset);
                int upperLimit = (nextClearBit == -1) ? 64 : nextClearBit;

                reserved = Math.min(64 - bitOffset, limit - total);
                reserved = Math.min(reserved, upperLimit - bitOffset);
                if (reserved == 0) {
                    return total;
                }
                clearMask = QueueUtils.clearMask(bitOffset, bitOffset + reserved);
            } while(!LA_HANDLE.compareAndSet(this.headSequence, pIdx, headSequence, headSequence + reserved));

            VarHandle.acquireFence();
            for (int j = 0; j < reserved; j++) {
                int qIdx = chunkIndex(head + j);
                consumer.consume(pQueue[qIdx]);
                pQueue[qIdx] = null;
            }
            total += reserved;
            LA_HANDLE.getAndBitwiseAnd(sequence, sChunkIdx, clearMask);

            VarHandle.releaseFence();
            LA_HANDLE.getAndAdd(this.heads, pIdx, reserved);
        }
        return total;
    }

    private void boundsCheck(int idx) {
        if (idx < 0 || idx >= partitions) {
            throw new IndexOutOfBoundsException(
                    "Index " + idx + " out of bounds for length " + partitions);
        }
    }

    private boolean drainCheck(QueueConsumer<T> consumer, int limit) {
        return consumer != null && limit > 0;
    }

    public int partitionIndex(int idx) {
        return ((idx + 1) * LONG_PAD) + idx;
    }

    public int queueIndex(long idx) {
        int logicalIdx = (int) (idx % partitions);
        return (logicalIdx + 1) * POINTER_PAD_BYTES + logicalIdx;
    }

    private int chunkIndex(long idx) {
        return (int) (idx & chunkMask) + POINTER_PAD_BYTES;
    }

    private int sequenceChunkIndex(long idx) {
        return (int) ((idx >>> 6) & sequenceMask) + LONG_PAD;
    }

    private long getSequenceNumber(long idx) {
        return 1L << (idx & 63);
    }

    public boolean isPartitionEmpty(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);

        int start = sequenceChunkIndex(0);
        int curr = start;
        do {
            long chunk = (long) LA_HANDLE.getVolatile(sequence[pIdx], curr);
            if (chunk != 0) {
                return false;
            }
            curr = sequenceChunkIndex(curr + 64);
        } while (curr != start);
        return true;
    }

    private boolean isPartitionEmptyInternal(int pIdx) {
        int start = sequenceChunkIndex(0);
        int curr = start;
        do {
            long chunk = (long) LA_HANDLE.getVolatile(sequence[pIdx], curr);
            if (chunk != 0) {
                return false;
            }
            curr = sequenceChunkIndex(curr + 64);
        } while (curr != start);
        return true;
    }

    public int getSize(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);

        long head = (long) LA_HANDLE.getOpaque(heads, pIdx);
        long tail = (long) LA_HANDLE.getOpaque(tails, pIdx);

        return (int) ((tail - head) & ABS_MASK);
    }

    public boolean isDrained(int partition) {
        if (unbounded && retired) {
            int pIdx = partitionIndex(partition);
            long head = (long) LA_HANDLE.getVolatile(heads, pIdx);
            long tail = (long) LA_HANDLE.getVolatile(tails, pIdx);
            long inFlight = (long) LA_HANDLE.getVolatile(this.inFlight, pIdx);

            int qTailIdx = chunkIndex(tail);
            int qHeadIdx = chunkIndex(head);
            return qHeadIdx == qTailIdx && inFlight == 0 && isPartitionEmptyInternal(pIdx);
        }
        return false;
    }

    public boolean isDrained() {
        if (unbounded && retired) {
            for (int i = 0; i < partitions; i++) {
                int pIdx = partitionIndex(i);
                long head = (long) LA_HANDLE.getVolatile(heads, pIdx);
                long tail = (long) LA_HANDLE.getVolatile(tails, pIdx);
                long inFlight = (long) LA_HANDLE.getVolatile(this.inFlight, pIdx);

                int qTailIdx = chunkIndex(tail);
                int qHeadIdx = chunkIndex(head);
                if (qHeadIdx != qTailIdx || inFlight > 0 || !isPartitionEmptyInternal(pIdx)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public void reset() {
        VarHandle.acquireFence();

        Arrays.fill(heads, 0);
        Arrays.fill(tails, 0);
        if(inFlight != null) {
            Arrays.fill(inFlight, 0);
        }
        for (int i = 0; i < partitions; i++) {
            int pIdx = partitionIndex(i);
            Arrays.fill(queue[queueIndex(i)], null);
            Arrays.fill(sequence[pIdx], 0);
        }
        retired = false;
    }

    public static long estimateFootprint(int partitions, boolean unbounded) {
        long queues = (partitions + 1L) * POINTER_PAD_BYTES + partitions;
        long heads = (partitions + 1L) * LONG_PAD + partitions;
        heads *= unbounded ? 4 : 3;
        return (queues + heads) * 8;
    }
}
