package euhedral.queues;

import static euhedral.queues.QueueUtils.ABS_MASK;
import static euhedral.queues.QueueUtils.POINTER_PAD_BYTES;
import static euhedral.queues.QueueUtils.LONG_PAD;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PartitionedMpmcXAddArrayQueue<T> implements PartitionedQueue<T> {

    private static final VarHandle LA_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);

    private final T[][] queue;
    private final long[][] sequence;
    private final int sequenceMask;

    private final boolean unbounded;
    private final int partitions;
    private final int chunkMask;

    private final long[] heads;
    private final long[] tails;
    private final long[] inFlight;

    @Getter
    private volatile boolean retired = false;

    @SuppressWarnings("unchecked")
    public PartitionedMpmcXAddArrayQueue(int partitions, int chunkSize, boolean unbounded) {
        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        this.unbounded = unbounded;
        this.partitions = partitions;
        this.chunkMask = chunkSize - 1;
        this.queue = (T[][]) new Object[partitions * POINTER_PAD_BYTES + 2 * POINTER_PAD_BYTES][0];
        this.sequence = new long[partitions * LONG_PAD + 2 * LONG_PAD][0];

        for (int i = 0; i < partitions; i++) {
            int pIdx = partitionIndex(i);
            this.queue[pIdx] = (T[]) new Object[chunkSize + POINTER_PAD_BYTES * 2];
            this.sequence[pIdx] = new long[(chunkSize >>> 6) + 2 * LONG_PAD];
        }

        this.sequenceMask = (chunkSize >>> 6) - 1;
        this.heads = new long[partitions * LONG_PAD + 2 * LONG_PAD];
        this.tails = new long[partitions * LONG_PAD + 2 * LONG_PAD];
        if (unbounded) {
            inFlight = new long[partitions * LONG_PAD + 2 * LONG_PAD];
        } else {
            inFlight = null;
        }
    }

    @Override
    public boolean offer(long randomSeed, T obj) {
        int partition = (int) QueueUtils.unsignedMultiplyHigh(randomSeed, this.partitions);
        return uncheckedOffer(partitionIndex(partition), obj);
    }

    @Override
    public boolean offer(int partition, T obj) {
        boundsCheck(partition);
        if (obj == null) {
            throw new NullPointerException();
        }
        return uncheckedOffer(partition, obj);
    }

    public boolean uncheckedOffer(int partition, T obj) {
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

        int qHeadIdx = queueIndex(head);
        int qTailIdx = queueIndex(tail);
        if (qTailIdx + 1 >= qHeadIdx) {
            LA_HANDLE.getAndAdd(tails, pIdx, -1);
            if (unbounded) {
                retired = true;
            }
            LA_HANDLE.getAndAdd(inFlight, pIdx, -1);
            return false;
        }

        T[] pQueue = queue[pIdx];
        pQueue[qTailIdx] = obj;

        LA_HANDLE.getAndBitwiseOr(sequence[pIdx], sequenceChunkIndex(tail),
                getSequenceNumber(qTailIdx));
        VarHandle.releaseFence();
        LA_HANDLE.getAndAdd(inFlight, pIdx, -1);
        return true;
    }

    @Override
    public int drain(T[] buffer, int offset, int limit) {
        if (buffer == null || buffer.length == 0 || offset >= buffer.length || limit <= 0) {
            return 0;
        }
        if (offset < 0) {
            throw new ArrayIndexOutOfBoundsException(
                    "Offset " + offset + " out of bounds for length " + buffer.length);
        }
        limit = Math.min(buffer.length - offset - 1, limit);
        if (limit == 0) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < partitions && limit > 0; i++) {
            int count = uncheckedDrain(i, buffer, offset, limit);
            offset += count;
            limit -= count;
            total += count;
        }
        return total;
    }

    @Override
    public int drain(int partition, T[] buffer, int offset, int limit) {
        boundsCheck(partition);
        if (buffer == null || buffer.length == 0 || offset >= buffer.length || limit <= 0) {
            return 0;
        }
        if (offset < 0) {
            throw new ArrayIndexOutOfBoundsException(
                    "Offset " + offset + " out of bounds for length " + buffer.length);
        }
        limit = Math.min(buffer.length - offset - 1, limit);
        if (limit == 0) {
            return 0;
        }

        return uncheckedDrain(partition, buffer, offset, limit);
    }

    public int uncheckedDrain(int pIdx, T[] buffer, int offset, int limit) {
        if (limit <= 0) {
            return 0;
        }

        VarHandle.acquireFence();

        long head = (long) LA_HANDLE.getVolatile(heads, pIdx);
        long[] sequence = this.sequence[pIdx];
        T[] pQueue = queue[pIdx];

        int total = 0;
        while (total < limit) {
            long slotIdx = head + total;
            int sChunkIdx = sequenceChunkIndex(slotIdx);
            int bitOffset = (int) (slotIdx & 63);

            long sChunk = (long) LA_HANDLE.getVolatile(sequence, sChunkIdx);

            int nextClearBit = QueueUtils.nextClearBit(sChunk, bitOffset);
            int upperLimit = (nextClearBit == -1) ? 64 : nextClearBit;

            int available = upperLimit - bitOffset;
            int claim = Math.min(available, limit - total);

            if (claim <= 0) {
                break;
            }

            long claimMask = QueueUtils.clearMask(bitOffset, bitOffset + claim);
            if (LA_HANDLE.compareAndSet(sequence, sChunkIdx, sChunk, sChunk & ~claimMask)) {
                for (int j = 0; j < claim; j++) {
                    int qIdx = queueIndex(slotIdx + j);
                    buffer[offset + total + j] = pQueue[qIdx];
                    pQueue[qIdx] = null;
                }
                total += claim;
            } else {
                break;
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

    private int partitionIndex(int idx) {
        return (idx << LONG_PAD) + LONG_PAD;
    }

    private int queueIndex(long idx) {
        return (int) (idx & chunkMask) + POINTER_PAD_BYTES;
    }

    private int sequenceChunkIndex(long qIdx) {
        return (int) ((qIdx >>> 6) & sequenceMask) + LONG_PAD;
    }

    private long getSequenceNumber(long qIdx) {
        return 1L << (qIdx & 63);
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
        Arrays.fill(heads, 0);
        Arrays.fill(tails, 0);
        Arrays.fill(inFlight, 0);
        for (int i = 0; i < partitions; i++) {
            int pIdx = partitionIndex(i);
            Arrays.fill(queue[pIdx], null);
            Arrays.fill(sequence[pIdx], 0);
        }
        retired = false;
    }
}
