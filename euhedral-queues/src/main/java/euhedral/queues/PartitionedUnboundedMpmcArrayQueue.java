package euhedral.queues;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PartitionedUnboundedMpmcArrayQueue<T> implements PartitionedQueue<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PartitionedUnboundedMpmcArrayQueue.class);

    private static final VarHandle HEADS = MethodHandles.arrayElementVarHandle(Node[].class);

    private final int partitions;
    private final int chunkSize;

    private final Node<T>[] heads;
    private volatile Node<T> tail;

    @SuppressWarnings("unchecked")
    public PartitionedUnboundedMpmcArrayQueue(int partitions, int chunkSize) {
        if(partitions <= 0 || chunkSize <= 0) {
            throw new IllegalArgumentException("Cannot have 0 partitions or 0 chunkSize: " + partitions + " " + chunkSize);
        }
        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        this.partitions = partitions;
        this.chunkSize = chunkSize;

        this.heads = new Node[partitions * QueueUtils.POINTER_PAD_BYTES + 2 * QueueUtils.POINTER_PAD_BYTES];
        this.tail = new Node<>(partitions, chunkSize);

        for(int i = 0; i < partitions; i++) {
            heads[partitionIndex(i)] = this.tail;
        }
    }

    /// Offers the object to a random partition
    @Override
    public boolean offer(long randomSeed, T obj) {
        int partition = (int) QueueUtils.unsignedMultiplyHigh(randomSeed, partitions);
        return offer(partition, obj);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean offer(int partition, T obj) {
        boundsCheck(partition);
        if(obj == null) {
            throw new NullPointerException();
        }

        Node<T> temp = null;
        int pIdx = partitionIndex(partition);

        boolean accepted;
        Node<T> tail = this.tail;
        do {
            accepted = tail.chunk.uncheckedOffer(pIdx, obj);
            if(!accepted && temp == null) {
                temp = new Node<>(partitions, chunkSize);
            }
            if(!accepted) {
                Node<T> prev = tail;
                tail = (Node<T>) Node.NEXT.compareAndExchange(tail, null, temp);
                if(tail == null) {
                    tail = temp;

                    if(this.tail == prev) {
                        this.tail = tail;
                    }
                }
            }

        } while(!accepted);
        return true;
    }

    /// Drains from all partitions starting from 0
    @Override
    public int drain(T[] buffer, int offset, int limit) {
        if(buffer == null || offset >= buffer.length || limit <= 0) {
            return 0;
        }
        if(offset < 0) {
            throw new ArrayIndexOutOfBoundsException("Offset " + offset + " out of bounds for length " + buffer.length);
        }

        int total = 0;
        for(int i = 0; i < this.partitions && total < limit; i++) {
            int count = uncheckedDrain(i, buffer, offset, limit);
            limit -= count;
            offset += count;
        }
        return total;
    }

    /// Drains from a specific partition
    @Override
    public int drain(int partition, T[] buffer, int offset, int limit) {
        boundsCheck(partition);
        if(buffer == null || offset >= buffer.length || limit <= 0) {
            return 0;
        }
        if(offset < 0) {
            throw new ArrayIndexOutOfBoundsException("Offset " + offset + " out of bounds for length " + buffer.length);
        }

        return uncheckedDrain(partition, buffer, offset, limit);
    }

    @SuppressWarnings("unchecked")
    private int uncheckedDrain(int partition, T[] buffer, int offset, int limit) {
        int pIdx = partitionIndex(partition);
        int total = 0;
        Node<T> head = (Node<T>) HEADS.getVolatile(heads, pIdx);
        do {
            int count = head.chunk.uncheckedDrain(pIdx, buffer, offset, limit);

            Node<T> next;
            if(count > 0) {
                limit -= count;
                offset += count;
                total += count;
            } else if((next = head.next) != null && head.chunk.isDrained(partition)) {
                Node<T> prev = head;
                head = (Node<T>) HEADS.compareAndExchange(heads, pIdx, head, next);
                Node.B_ARRAY.setVolatile(prev.refs, partition, false);

                if(prev.isRetired() && prev.reclaimed.compareAndSet(false, true)) {
                    // Recycle
                }
            } else {
                break;
            }
        } while(limit > 0);
        return total;
    }

    private void boundsCheck(int idx) {
        if (idx < 0 || idx >= partitions) {
            throw new IndexOutOfBoundsException(
                    "Index " + idx + " out of bounds for length " + partitions);
        }
    }

    private int partitionIndex(int idx) {
        return (idx << QueueUtils.LONG_PAD) + QueueUtils.LONG_PAD;
    }

    private static class Node<T> {
        static final VarHandle NEXT;
        static final VarHandle B_ARRAY = MethodHandles.arrayElementVarHandle(boolean[].class);

        static {
            VarHandle handle = null;
            try {
                handle = MethodHandles.lookup().findVarHandle(Node.class, "next", Node.class);
            } catch (Throwable t) {
                LOGGER.error("Error initializing VarHandle", t);
            }
            NEXT = handle;
        }

        final AtomicBoolean reclaimed = new AtomicBoolean(false);
        final PartitionedMpmcXAddArrayQueue<T> chunk;
        volatile Node<T> next;

        final int partitions;
        final boolean[] refs;

        public Node(int partitions, int chunkSize) {
            this.partitions = partitions;
            chunk = new PartitionedMpmcXAddArrayQueue<>(partitions, chunkSize, true);
            refs = new boolean[partitions];
            Arrays.fill(refs, true);
        }

        public boolean isRetired() {
            VarHandle.acquireFence();
            for(int i = 0; i < partitions; i++) {
                if((boolean) B_ARRAY.getVolatile(refs, i)) {
                    return false;
                }
            }
            return true;
        }

        private static long countDown(long l1, long l2) {
            return Math.max(0, l1 - l2);
        }
    }
}
