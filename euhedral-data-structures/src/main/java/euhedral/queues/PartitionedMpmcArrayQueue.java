package euhedral.queues;

import static euhedral.queues.QueueUtils.ABS_MASK;
import static euhedral.queues.QueueUtils.POINTER_PAD_BYTES;
import static euhedral.queues.QueueUtils.LONG_PAD;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.StringJoiner;

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
public class PartitionedMpmcArrayQueue<T> extends PartitionedArrayQueue<T> {

    private static final VarHandle LA_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[][] sequence;
    private final int sequenceMask;

    private final long[] headSequence;
    private final long[] inFlight;

    @Getter
    private volatile boolean retired = false;

    public PartitionedMpmcArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        super(partitions, chunkSize, unbounded);
        this.sequence = new long[(partitions + 1) * LONG_PAD + partitions][0];

        int numSChunks = Math.max(chunkSize >>> 6, 1);
        this.sequenceMask = numSChunks - 1;
        for (int i = 0; i < partitions; i++) {
            int pIdx = partitionIndex(i);
            this.sequence[pIdx] = new long[numSChunks + 2 * LONG_PAD];
        }

        this.headSequence = new long[(partitions + 1) * LONG_PAD + partitions];
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

        if (unbounded && retired) {
            return false;
        }
        int pIdx = partitionIndex(partition);

        if(unbounded) {
            LA_HANDLE.getAndAdd(inFlight, pIdx, 1);
        }

        long head;
        long tail;
        do {
            head = (long) LA_HANDLE.getVolatile(heads, pIdx);
            tail = (long) LA_HANDLE.getVolatile(tails, pIdx);
            if(QueueUtils.unsignedDiff(head, tail + 1) > chunkSize) {
                if(unbounded) {
                    retired = true;
                    LA_HANDLE.getAndAdd(inFlight, pIdx, -1);
                }
                return false;
            }
        } while(!LA_HANDLE.compareAndSet(tails, pIdx, tail, tail + 1));

        int qTailIdx = chunkIndex(tail);
        T[] pQueue = queue[queueIndex(partition)];
        pQueue[qTailIdx] = obj;

        VarHandle.releaseFence();

        LA_HANDLE.getAndAdd(sequence[pIdx], sequenceChunkIndex(tail),
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

    private int sequenceChunkIndex(long idx) {
        return (int) ((idx >>> 6) & sequenceMask) + LONG_PAD;
    }

    private long getSequenceNumber(long idx) {
        return 1L << (idx & 63);
    }

    public int getSize(int partition) {
        boundsCheck(partition);
        int pIdx = partitionIndex(partition);

        long head = (long) LA_HANDLE.getOpaque(heads, pIdx);
        long tail = (long) LA_HANDLE.getOpaque(tails, pIdx);

        return (int) ((tail - head) & ABS_MASK);
    }

    public boolean isEmpty() {
        for (int i = 0; i < partitions; i++) {
            if (!isEmpty(i)) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmpty(int partition) {
        int pIdx = partitionIndex(partition);
        long head = (long) LA_HANDLE.getVolatile(heads, pIdx);
        long tail = (long) LA_HANDLE.getVolatile(tails, pIdx);
        long headSeq = (long) LA_HANDLE.getVolatile(headSequence, pIdx);
        long inFlight = (long) LA_HANDLE.getVolatile(this.inFlight, pIdx);

        return head == tail && head == headSeq && inFlight == 0 && isPartitionEmptyInternal(pIdx);
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

    public void reset() {
        VarHandle.acquireFence();

        Arrays.fill(heads, 0);
        Arrays.fill(tails, 0);
        Arrays.fill(headSequence, 0);
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

    public String getState() {
        StringJoiner sj = new StringJoiner("\n");
        sj.add("Heads: " + Arrays.toString(heads));
        sj.add("Tails: " + Arrays.toString(tails));
        sj.add("HeadSequence: " + Arrays.toString(headSequence));
        for(int i = 0; i < partitions; i++) {
            sj.add("TailSequence-p" + i + ": " + Arrays.toString(sequence[partitionIndex(i)]));
        }
        return sj.toString();
    }

    public static long estimateFootprint(int partitions, boolean unbounded) {
        long queues = (partitions + 1L) * POINTER_PAD_BYTES + partitions;
        long heads = (partitions + 1L) * LONG_PAD + partitions;
        heads *= unbounded ? 4 : 3;
        return (queues + heads) * 8;
    }
}
