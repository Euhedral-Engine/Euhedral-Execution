package euhedral.queues.common;

import euhedral.queues.PartitionedArrayQueue;
import euhedral.queues.PartitionedMpmcArrayQueue;
import euhedral.queues.PartitionedMpscArrayQueue;
import euhedral.queues.PartitionedSpmcArrayQueue;
import euhedral.queues.PartitionedSpscArrayQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class QueueNode<T> {
    public static final VarHandle B_ARRAY = MethodHandles.arrayElementVarHandle(boolean[].class);

    public final AtomicBoolean reclaimed = new AtomicBoolean(false);
    public final PartitionedArrayQueue<T> chunk;

    public final int partitions;
    private final boolean[] refs;

    public final AtomicReference<QueueNode<T>> next = new AtomicReference<>();
    public final Type type;

    private final AtomicLong epoch = new AtomicLong(0);

    public QueueNode(int partitions, int chunkSize, Type type) {
        this.partitions = partitions;
        refs = new boolean[partitions];
        Arrays.fill(refs, true);

        this.type = type;
        chunk = switch (type) {
            case SPSC -> new PartitionedSpscArrayQueue<>(partitions, chunkSize, true);
            case SPMC -> new PartitionedSpmcArrayQueue<>(partitions, chunkSize, true);
            case MPMC -> new PartitionedMpmcArrayQueue<>(partitions, chunkSize, true);
            case MPSC -> new PartitionedMpscArrayQueue<>(partitions, chunkSize, true);
            case PLAIN -> new PartitionedArrayQueue<>(partitions, chunkSize, true);
        };
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

    public boolean getRef(int partition) {
        return switch (this.type) {
            case PLAIN -> this.refs[partition];
            case SPSC, MPSC -> (boolean) B_ARRAY.getOpaque(this.refs, partition);
            default -> (boolean) B_ARRAY.getAcquire(this.refs, partition);
        };
    }

    public boolean casRef(int partition, boolean expected, boolean update) {
        return switch (this.type) {
            case PLAIN -> {
                if(this.refs[partition] != expected) {
                    yield false;
                }
                this.refs[partition] = update;
                yield true;
            }
            case SPSC, MPSC -> {
                if((boolean) B_ARRAY.getOpaque(this.refs, partition) != expected) {
                    yield false;
                }
                B_ARRAY.setOpaque(this.refs, partition, update);
                yield true;
            }
            default -> B_ARRAY.compareAndSet(this.refs, partition, expected, update);
        };
    }

    public long getEpoch() {
        return switch(this.type) {
            case PLAIN -> this.epoch.getPlain();
            case SPSC, MPSC -> this.epoch.getOpaque();
            default -> this.epoch.getAcquire();
        };
    }

    public long getAndIncrementEpoch() {
        return switch (this.type) {
            case PLAIN -> {
                this.epoch.setPlain(this.epoch.getPlain() + 1);
                yield this.epoch.getPlain();
            }
            case SPSC, MPSC -> {
                this.epoch.setOpaque(this.epoch.getPlain() + 1);
                yield this.epoch.getOpaque();
            }
            default -> this.epoch.getAndIncrement();
        };
    }

    public void reset() {
        chunk.reset();
        for(int i = 0; i < partitions; i++) {
            B_ARRAY.setRelease(refs, i, true);
        }
        reclaimed.lazySet(false);
        next.setRelease(null);
    }

    public enum Type {
        PLAIN,
        SPSC,
        SPMC,
        MPMC,
        MPSC
    }
}
