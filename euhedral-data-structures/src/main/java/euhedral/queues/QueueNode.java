package euhedral.queues;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class QueueNode<T> {

    private static final VarHandle LA_ARRAY = MethodHandles.arrayElementVarHandle(long[].class);

    public final Type type;
    public final int partitions;
    public final AtomicReference<QueueNode<T>> next = new AtomicReference<>();
    private final PartitionedArrayQueue<T> chunk;
    private final long[] headEpoch;
    private final AtomicLong tailEpoch;

    public QueueNode(int partitions, int chunkSize, Type type) {
        this.partitions = partitions;

        this.type = type;
        chunk = switch (type) {
            case PLAIN -> new PartitionedArrayQueue<>(partitions, chunkSize, true);
            case SPSC -> new PartitionedSpscArrayQueue<>(partitions, chunkSize, true);
            case SPMC -> new PartitionedSpmcArrayQueue<>(partitions, chunkSize, true);
            case MPSC -> new PartitionedMpscArrayQueue<>(partitions, chunkSize, true);
            case MPMC -> new PartitionedMpmcArrayQueue<>(partitions, chunkSize, true);
        };

        if (type == Type.MPSC || type == Type.MPMC) {
            this.tailEpoch = new AtomicLong(0);
        } else {
            this.tailEpoch = null;
        }

        if (type == Type.SPMC || type == Type.MPMC) {
            this.headEpoch = new long[partitions];
        } else {
            this.headEpoch = null;
        }
    }

    public boolean offer(int partition, T obj) {
        return this.chunk.offer(partition, obj);
    }

    public T peek(int partition) {
        return this.chunk.peek(partition);
    }

    public T poll(int partition) {
        return this.chunk.poll(partition);
    }

    public int drain(int partition, QueueConsumer<T> consumer, int limit) {
        return this.chunk.drain(partition, consumer, limit);
    }

    public boolean isRetired() {
        return chunk.isRetired();
    }

    public boolean isEmpty() {
        return chunk.isEmpty();
    }

    public boolean isEmpty(int partition) {
        return chunk.isEmpty(partition);
    }

    public long size() {
        return chunk.size();
    }

    public long size(int partition) {
        return chunk.size(partition);
    }

    public long getHeadEpoch(int partition) {
        return this.headEpoch == null ? 0 : (long) LA_ARRAY.getAcquire(this.headEpoch, partition);
    }

    public boolean casHeadEpoch(int partition, long expected, long update) {
        return this.headEpoch == null || LA_ARRAY.compareAndSet(this.headEpoch, partition, expected,
                update);
    }

    public long getTailEpoch() {
        return this.tailEpoch == null ? 0 : this.tailEpoch.getAcquire();
    }

    public void setTailEpoch(long epoch) {
        if (this.tailEpoch != null) {
            this.tailEpoch.setRelease(epoch);
        }
    }

    public void clear() {
        this.chunk.clear();
        if (this.type == Type.PLAIN) {
            this.next.setPlain(null);
        } else {
            this.next.setRelease(null);
        }
    }

    @Override
    public String toString() {
        StringJoiner sj = new StringJoiner("\n");
        sj.add(String.format("Type: %s Partitions: %d Hash: %d", this.type, this.partitions,
                hashCode()));
        sj.add(String.format("Tail Epoch: %d", this.tailEpoch == null ? 0 : this.tailEpoch.get()));
        sj.add(String.format("Head Epoch: %s",
                this.headEpoch == null ? "[0]" : Arrays.toString(this.headEpoch)));
        sj.add("Chunk: " + this.chunk);
        return sj.toString();
    }

    public enum Type {
        PLAIN,
        SPSC,
        SPMC,
        MPMC,
        MPSC
    }
}
