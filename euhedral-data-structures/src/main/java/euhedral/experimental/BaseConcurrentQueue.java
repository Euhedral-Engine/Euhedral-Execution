package euhedral.experimental;

import static euhedral.queues.common.QueueUtils.HALF_INCREMENT;
import static euhedral.queues.common.QueueUtils.INCREMENT;

import euhedral.queues.common.QueueUtils;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public abstract class BaseConcurrentQueue extends AbstractConcurrentQueue {

    private static void linkChunk(Object[] oldQueue, Object[] nextQueue, int cIdx) {
        oldQueue[oldQueue.length - 1] = nextQueue;

        // Publish must happen after link.
        QueueUtils.storeVolatile(oldQueue, cIdx, QueueUtils.SENTINEL);
    }

    protected final long chunkMask;
    protected final int linkIndex;

    protected BaseConcurrentQueue(int chunkSize) {
        super(new Object[QueueUtils.queueSize(chunkSize)]);
        this.chunkMask = QueueUtils.chunkMask(chunkSize);
        this.linkIndex = this.headQueue.length - 1;
    }

    protected Object[] allocateChunk(int chunkSize) {
        return new Object[chunkSize];
    }

    protected void freeChunk(Object[] chunk) {
    }

    // ----- Single Producer -----
    protected final void spFill(Object[] objs) {
        Objects.requireNonNull(objs);
        for (Object obj : objs) {
            spOffer(obj);
        }
    }

    protected final void spFill(Iterable<Object> objs) {
        Objects.requireNonNull(objs);
        for (Object obj : objs) {
            spOffer(obj);
        }
    }

    protected final void spOffer(Object obj) {
        Objects.requireNonNull(obj);

        long tail = getTailPlain(this);
        long tailEpoch = getTailEpochPlain(this);

        if ((tailEpoch - tail) > 0) {
            storeInTailQueuePlain(this, QueueUtils.chunkIndex(tail, this.chunkMask), obj);
            setTailRelease(this, tail + INCREMENT);
            return;
        }

        int cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);
        long head = getHeadAcquire(this);

        if (head + this.chunkMask > tail) {
            setTailEpochPlain(this, head + this.chunkMask);
            storeInTailQueuePlain(this, cIdx, obj);
            getAndAddTail(this, INCREMENT);
            return;
        }

        Object[] oldQueue = getTailQueuePlain(this);
        Object[] nextQueue = allocateChunk(oldQueue.length);
        nextQueue[cIdx] = obj;

        linkChunk(oldQueue, nextQueue, cIdx);

        setTailQueuePlain(this, nextQueue);
        addTailEpochPlain(this, this.chunkMask);
        getAndAddTail(this, INCREMENT);
    }

    // ----- Multi Producer -----

    protected final void mpFill(Object[] objs) {
        for (Object obj : objs) {
            mpOffer(obj);
        }
    }

    protected final void mpFill(Iterable<Object> objs) {
        for (Object obj : objs) {
            mpOffer(obj);
        }
    }

    protected final void mpOffer(Object obj) {
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

            if ((epoch - tail) > 0) {
                if (!casTail(this, tail, tail + INCREMENT)) {
                    continue;
                }
                cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);
                break;
            }

            // Update epoch
            long head = getHeadAcquire(this);
            if (head + this.chunkMask > tail) {
                if(!casTailEpoch(this, epoch, head + this.chunkMask)) {
                    continue;
                }

                if(casTail(this, tail, tail + INCREMENT)) {
                    cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);
                    break;
                }
                continue;
            }

            if(casTail(this, tail, tail + HALF_INCREMENT)) {
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
    }

    // ----- Single Consumer -----

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
            freeChunk(queue);
            setHeadQueuePlain(this, temp);

            obj = QueueUtils.loadAcquire(temp, cIdx);
            QueueUtils.storeVolatile(queue, this.linkIndex, null);
        }

        return obj;
    }

    public final long sizeLong() {
        long head = getHeadAcquire(this);
        long tail = getTailAcquire(this);
        return tail - head;
    }

    public final int size() {
        return (int) Math.min(sizeLong(), Integer.MAX_VALUE);
    }
}
