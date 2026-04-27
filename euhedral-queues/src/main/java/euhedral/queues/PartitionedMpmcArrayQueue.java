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
            this.queue[pIdx] = (T[]) new Object[chunkSize + POINTER_PAD_BYTES * 2];
            this.sequence[pIdx] = new long[numSChunks + 2 * LONG_PAD];
        }

        this.heads = new long[(partitions + 1) * LONG_PAD + partitions];
        this.tails = new long[(partitions + 1) * LONG_PAD + partitions];
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

        long head = (long) LA_HANDLE.getOpaque(heads, pIdx);
        long tail = (long) LA_HANDLE.getAndAdd(tails, pIdx, 1);

        if (QueueUtils.unsignedDiff(head, tail + 1) > chunkSize) {
            LA_HANDLE.getAndAdd(tails, pIdx, -1);
            if (unbounded) {
                retired = true;
                LA_HANDLE.getAndAdd(inFlight, pIdx, -1);
            }
            return false;
        }

        int qTailIdx = queueIndex(tail);
        T[] pQueue = queue[pIdx];
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
        T[] pQueue = queue[pIdx];

        long head = (long) LA_HANDLE.getVolatile(heads, pIdx);
        long[] sequence = this.sequence[pIdx];

        int sChunkIdx = sequenceChunkIndex(head);
        long sNum = getSequenceNumber(head);

        long sChunk = (long) LA_HANDLE.getVolatile(sequence, sChunkIdx);

        if ((sNum & sChunk) == 0) {
            return null;
        }
        return pQueue[queueIndex(head)];
    }

    @Override
    public T poll(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);
        T[] pQueue = queue[pIdx];

        while (true) {
            long head = (long) LA_HANDLE.getVolatile(heads, pIdx);
            long[] sequence = this.sequence[pIdx];

            int sChunkIdx = sequenceChunkIndex(head);
            long sNum = getSequenceNumber(head);

            long sChunk = (long) LA_HANDLE.getVolatile(sequence, sChunkIdx);

            if ((sNum & sChunk) == 0) {
                return null;
            }
            T obj = pQueue[queueIndex(head)];
            if (LA_HANDLE.compareAndSet(sequence, sChunkIdx, sChunk, sChunk & ~sNum)) {
                LA_HANDLE.getAndAdd(heads, pIdx, 1);
                return obj;
            }
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

        long head = (long) LA_HANDLE.getVolatile(heads, pIdx);
        long[] sequence = this.sequence[pIdx];
        T[] pQueue = queue[pIdx];

        int total = 0;
        while (total < limit) {
            long slot = head + total;

            int sChunkIdx = sequenceChunkIndex(slot);
            int bitOffset = (int) (slot & 63);

            long sChunk = (long) LA_HANDLE.getVolatile(sequence, sChunkIdx);

            int nextClearBit = QueueUtils.nextClearBit(sChunk, bitOffset);
            int upperLimit = (nextClearBit == -1) ? 64 : nextClearBit;

            int available = upperLimit - bitOffset;
            int claim = Math.min(available, limit - total);

            if (claim <= 0) {
                break;
            }

            long claimMask = QueueUtils.clearMask(bitOffset, bitOffset + claim);
            if (LA_HANDLE.compareAndSet(sequence, sChunkIdx, sChunk, sChunk & claimMask)) {
                VarHandle.acquireFence();
                for (int j = 0; j < claim; j++) {
                    int qIdx = queueIndex(slot + j);
                    consumer.consume(pQueue[qIdx]);
                    pQueue[qIdx] = null;
                }
                total += claim;
            }
        }

        if (total > 0) {
            LA_HANDLE.getAndAdd(heads, pIdx, total);
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
        return (idx * LONG_PAD) + LONG_PAD + idx;
    }

    private int queueIndex(long idx) {
        return (int) (idx & chunkMask) + POINTER_PAD_BYTES;
    }

    private int sequenceChunkIndex(long idx) {
        return (int) ((idx >>> 6) & sequenceMask) + LONG_PAD;
    }

    private long getSequenceNumber(long qIdx) {
        return 1L << (qIdx & 63);
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

            int qTailIdx = queueIndex(tail);
            int qHeadIdx = queueIndex(head);
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

                int qTailIdx = queueIndex(tail);
                int qHeadIdx = queueIndex(head);
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
            Arrays.fill(queue[pIdx], null);
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
