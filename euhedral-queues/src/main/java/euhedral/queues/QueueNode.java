package euhedral.queues;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueueNode<T> {
    private static final AtomicInteger ID = new AtomicInteger(0);
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueNode.class);

    public static final VarHandle NEXT;
    public static final VarHandle B_ARRAY = MethodHandles.arrayElementVarHandle(boolean[].class);

    static {
        VarHandle handle = null;
        try {
            handle = MethodHandles.lookup().findVarHandle(QueueNode.class, "next", QueueNode.class);
        } catch (Throwable t) {
            LOGGER.error("Error initializing VarHandle", t);
        }
        NEXT = handle;
    }

    public final int id;
    public final AtomicBoolean reclaimed = new AtomicBoolean(false);
    public final PartitionedMpmcArrayQueue<T> chunk;

    public final int partitions;
    public final boolean[] refs;

    public volatile QueueNode<T> next;

    public QueueNode(int partitions, int chunkSize) {
        this.id = ID.incrementAndGet();

        this.partitions = partitions;
        chunk = new PartitionedMpmcArrayQueue<>(partitions, chunkSize, true);
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
}
