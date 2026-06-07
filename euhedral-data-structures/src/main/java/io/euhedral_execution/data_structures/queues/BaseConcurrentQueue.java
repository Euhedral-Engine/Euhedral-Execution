package io.euhedral_execution.data_structures.queues;

import static io.euhedral_execution.data_structures.queues.common.QueueUtils.HALF_INCREMENT;
import static io.euhedral_execution.data_structures.queues.common.QueueUtils.INCREMENT;
import static io.euhedral_execution.data_structures.queues.common.QueueUtils.SHIFT;

import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Consumer;

@SuppressWarnings({"rawtypes","unused"})
public abstract class BaseConcurrentQueue<T> extends AbstractConcurrentQueue {

    private static void linkChunk(Object[] oldQueue, Object[] nextQueue, int cIdx) {
        oldQueue[oldQueue.length - 1] = nextQueue;

        // Publish must happen after link.
        QueueUtils.storeVolatile(oldQueue, cIdx, QueueUtils.SENTINEL);
    }

    protected final long chunkMask;
    protected final int linkIndex;
    protected final boolean bounded;

    protected BaseConcurrentQueue(int chunkSize, boolean bounded) {
        super(new Object[QueueUtils.queueSize(Math.max(chunkSize, 2))]);
        this.chunkMask = QueueUtils.chunkMask(Math.max(chunkSize, 2));
        this.linkIndex = this.headQueue.length - 1;
        this.bounded = bounded;
    }

    public abstract boolean offer(T obj);

    public abstract T peek();

    public abstract T poll();

    public abstract void fill(T[] objs);

    public abstract void fill(Collection<T> objs);

    public abstract long drain(Consumer<T> consumer, long limit);

    public abstract void clear();

    protected Object[] allocateChunk(int chunkSize) {
        return new Object[chunkSize];
    }

    protected void freeChunk(Object[] chunk) {
    }

    // ----- Single Producer -----

    protected final int spFill(Object[] objs) {
        return spFill(objs, 0, objs.length);
    }

    /// Queue fill logic for single-producer queues
    protected final int spFill(Object[] objs, int start, int end) {
        Objects.requireNonNull(objs);
        if(end - start <= 0) {
            return 0;
        }

        int total = 0;
        long size = (long) (end - start) << SHIFT;
        while (size > 0) {
            long tail = getTailPlain(this);
            long epoch = getTailEpochPlain(this);

            long claim = Math.min(size, epoch - tail);

            // Slow Path
            if(claim == 0) {
                if(!spEpochUpdate(tail, epoch)) {
                    break;
                }
                continue;
            }

            size -= claim;

            // Fast Path
            while(claim > 0) {
                Object obj = objs[start++];
                Objects.requireNonNull(obj, "null elements cannot be inserted into this queue");

                int cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);
                storeInTailQueuePlain(this, cIdx, obj);
                claim -= INCREMENT;
                tail += INCREMENT;
                total++;
            }
            setTailRelease(this, tail);
        }
        return total;
    }

    /// Queue fill logic for single-producer queues
    protected final int spFill(Collection<Object> objs) {
        Objects.requireNonNull(objs);

        int total = 0;
        long size = (long) objs.size() << SHIFT;
        Iterator<Object> iter = objs.iterator();

        while (size > 0) {
            long tail = getTailPlain(this);
            long epoch = getTailEpochPlain(this);

            long claim = Math.min(size, epoch - tail);

            // Slow Path
            if(claim == 0) {
                if(!spEpochUpdate(tail, epoch)) {
                    break;
                }
                continue;
            }

            size -= claim;

            // Fast Path
            while(claim > 0 && iter.hasNext()) {
                Object obj = iter.next();
                Objects.requireNonNull(obj, "null elements cannot be inserted into this queue");

                int cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);
                storeInTailQueuePlain(this, cIdx, obj);
                claim -= INCREMENT;
                tail += INCREMENT;
                total++;
            }
            setTailRelease(this, tail);
        }
        return total;
    }

    private boolean spEpochUpdate(long tail, long epoch) {
        int cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);
        long head = getHeadAcquire(this);

        if (head + this.chunkMask > tail) {
            setTailEpochPlain(this, head + this.chunkMask);
            return true;
        }

        if(this.bounded) {
            return false;
        }

        Object[] oldQueue = getTailQueuePlain(this);
        Object[] nextQueue = allocateChunk(oldQueue.length);

        linkChunk(oldQueue, nextQueue, cIdx);

        setTailQueuePlain(this, nextQueue);
        addTailEpochPlain(this, this.chunkMask);
        return true;
    }

    /// Queue offer logic for single-producer queues
    protected final boolean spOffer(Object obj) {
        Objects.requireNonNull(obj);

        long tail = getTailPlain(this);
        long tailEpoch = getTailEpochPlain(this);

        if ((tailEpoch - tail) > 0) {
            storeInTailQueuePlain(this, QueueUtils.chunkIndex(tail, this.chunkMask), obj);
            setTailRelease(this, tail + INCREMENT);
            return true;
        }

        int cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);
        long head = getHeadAcquire(this);

        if (head + this.chunkMask > tail) {
            setTailEpochPlain(this, head + this.chunkMask);
            storeInTailQueuePlain(this, cIdx, obj);
            getAndAddTail(this, INCREMENT);
            return true;
        }

        if(this.bounded) {
            return false;
        }

        Object[] oldQueue = getTailQueuePlain(this);
        Object[] nextQueue = allocateChunk(oldQueue.length);
        nextQueue[cIdx] = obj;

        linkChunk(oldQueue, nextQueue, cIdx);

        setTailQueuePlain(this, nextQueue);
        addTailEpochPlain(this, this.chunkMask);
        getAndAddTail(this, INCREMENT);
        return true;
    }

    // ----- Single Consumer -----

    /// Queue drain logic for single-consumer queues
    protected final long scDrain(Consumer<Object> consumer, long limit) {
        Objects.requireNonNull(consumer);
        if (limit <= 0) {
            return 0;
        }

        // Acquire before touching the queue.
        VarHandle.acquireFence();

        long head = getHeadPlain(this);

        Object[] queue = getHeadQueuePlain(this);

        long total = 0;
        while (total < limit) {
            int cIdx = QueueUtils.chunkIndex(head, this.chunkMask);
            Object obj = queue[cIdx];

            if (obj == null) {
                break;
            }

            queue[cIdx] = null;
            if (obj == QueueUtils.SENTINEL) {
                Object[] retired = queue;
                queue = (Object[]) queue[this.linkIndex];
                retired[this.linkIndex] = null;

                freeChunk(retired);

                setHeadQueuePlain(this, queue);
                continue;
            }

            consumer.accept(obj);
            head += INCREMENT;
            total++;
        }

        setHeadRelease(this, head);

        return total;
    }

    /// Queue poll logic for single-consumer queues
    protected final Object scPoll() {
        long head = getHeadPlain(this);
        int cIdx = QueueUtils.chunkIndex(head, this.chunkMask);
        Object obj = this.scPeekInternal(cIdx);

        if (obj != null) {
            setHeadQueueSlotPlain(this, cIdx, null);
            setHeadRelease(this, head + INCREMENT);
        }
        return obj;
    }

    /// Queue peek logic for single-consumer queues
    protected final Object scPeek() {
        long head = getHeadPlain(this);
        int cIdx = QueueUtils.chunkIndex(head, this.chunkMask);
        return scPeekInternal(cIdx);
    }

    private Object scPeekInternal(int cIdx) {
        Object[] queue = getHeadQueuePlain(this);

        Object obj = QueueUtils.loadAcquire(queue, cIdx);

        if (obj == null) {
            return null;
        }

        if (obj == QueueUtils.SENTINEL) {
            Object[] temp = (Object[]) QueueUtils.loadAcquire(queue, this.linkIndex);
            queue[cIdx] = null;
            queue[this.linkIndex] = null;

            freeChunk(queue);
            setHeadQueuePlain(this, temp);

            obj = QueueUtils.loadAcquire(temp, cIdx);
            QueueUtils.storeVolatile(queue, this.linkIndex, null);
        }

        return obj;
    }

    // ----- Multi Producer -----

    /// Queue fill logic for multi-producer queues
    protected final void mpFill(Object[] objs) {
        for (Object obj : objs) {
            if(!mpOffer(obj)) {
                return;
            }
        }
    }

    /// Queue fill logic for multi-producer queues
    protected final void mpFill(Collection<Object> objs) {
        Objects.requireNonNull(objs);

        for (Object obj : objs) {
            if(!mpOffer(obj)) {
                return;
            }
        }
    }

    /// Queue offer logic for multi-producer queues
    protected final boolean mpOffer(Object obj) {
        Objects.requireNonNull(obj);

        int cIdx;
        Object[] queue;
        while (true) {
            long tail = getTailAcquire(this);

            // If odd, resizing.
            if ((tail & HALF_INCREMENT) == 1) {
                continue;
            }

            long epoch = getTailEpochAcquire(this);

            // Get the current chunk before trying to claim a slot
            queue = getTailQueuePlain(this);

            ClaimStatus status = mpClaim(tail, epoch, INCREMENT);
            if(status == ClaimStatus.SUCCESS) {
                cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);
                break;
            }
            if(status == ClaimStatus.FAILURE) {
                continue;
            }

            if(this.bounded) {
                return false;
            }

            if (casTail(this, tail, tail + HALF_INCREMENT)) {
                cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);

                Object[] nextQueue = allocateChunk(queue.length);
                setTailQueuePlain(this, nextQueue);
                setTailEpochPlain(this, tail + this.chunkMask);
                getAndAddTail(this, HALF_INCREMENT);

                linkChunk(queue, nextQueue, cIdx);
                queue = nextQueue;
                break;
            }
        }
        QueueUtils.storeRelease(queue, cIdx, obj);
        return true;
    }

    private ClaimStatus mpClaim(long tail, long epoch, long delta) {
        long claim = tail + delta;
        if(claim <= epoch) {
            if (!casTail(this, tail, claim)) {
                return ClaimStatus.FAILURE;
            }
            return ClaimStatus.SUCCESS;
        }

        long head = getHeadAcquire(this);
        long nextEpoch = head + this.chunkMask;
        if((nextEpoch - tail) <= 0) {
            return ClaimStatus.RESIZE;
        }

        if(!casTailEpoch(this, epoch, nextEpoch)) {
            return ClaimStatus.FAILURE;
        }
        if(!casTail(this, tail, claim)) {
            return ClaimStatus.FAILURE;
        }
        return ClaimStatus.SUCCESS;
    }

    public final long sizeLong() {
        long head = getHeadAcquire(this);
        long tail = getTailAcquire(this);
        return tail - head;
    }

    public final int size() {
        return (int) Math.min(sizeLong(), Integer.MAX_VALUE);
    }

    public final boolean isEmpty() {
        return sizeLong() == 0;
    }

    private enum ClaimStatus {
        SUCCESS,
        FAILURE,
        RESIZE
    }

    private abstract static class MCAccessFlag<T> extends BaseConcurrentQueue<T> {

        protected static final VarHandle MC_FLAG;

        static {
            try {
                MC_FLAG = MethodHandles.lookup()
                        .findVarHandle(MCAccessFlag.class, "mcFlag", long.class);
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        long mcFlag = 0;

        MCAccessFlag(int chunkSize, boolean bounded) {
            super(chunkSize, bounded);
        }

        protected static boolean acquireMcLock(BaseConcurrentQueue impl) {
            return MC_FLAG.compareAndSet(impl, 0, 1);
        }

        protected static void releaseMcLock(BaseConcurrentQueue impl) {
            MC_FLAG.setRelease(impl, 0);
        }
    }

    public abstract static class MultiConsumer<T> extends MCAccessFlag<T> {

        private long p00, p01, p02, p03, p04, p05, p06, p07;
        private long p08, p09, p10, p11, p12, p13, p14, p15;

        MultiConsumer(int chunkSize, boolean bounded) {
            super(chunkSize, bounded);
        }
    }
}
