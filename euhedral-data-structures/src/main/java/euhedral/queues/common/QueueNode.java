package euhedral.queues.common;

import euhedral.queues.PartitionedArrayQueue;
import euhedral.queues.PartitionedMpmcArrayQueue;
import euhedral.queues.PartitionedMpscArrayQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class QueueNode<T> {
    public static final VarHandle B_ARRAY = MethodHandles.arrayElementVarHandle(boolean[].class);

    public final AtomicBoolean reclaimed = new AtomicBoolean(false);
    public final PartitionedArrayQueue<T> chunk;

    public final int partitions;
    public final boolean[] refs;

    public final AtomicReference<QueueNode<T>> next = new AtomicReference<>();

    public QueueNode(int partitions, int chunkSize, Type type) {
        this.partitions = partitions;
        refs = new boolean[partitions];
        Arrays.fill(refs, true);

        chunk = switch (type) {
            case MPMC -> new PartitionedMpmcArrayQueue<>(partitions, chunkSize, true);
            case MPSC -> new PartitionedMpscArrayQueue<>(partitions, chunkSize, true);
            case UNSAFE -> new PartitionedArrayQueue<>(partitions, chunkSize, true);
        };
    }

    public boolean isEmpty() {
        return chunk.isEmpty();
    }

    public boolean isRetired() {
        for(int i = 0; i < partitions; i++) {
            if((boolean) B_ARRAY.getAcquire(refs, i)) {
                return false;
            }
        }
        return true;
    }

    public void reset() {
        chunk.reset();
        reclaimed.lazySet(false);
        for(int i = 0; i < partitions; i++) {
            B_ARRAY.setRelease(refs, i, true);
        }
        next.setRelease(null);
    }

    public enum Type {
        MPMC,
        MPSC,
        UNSAFE
    }
}
