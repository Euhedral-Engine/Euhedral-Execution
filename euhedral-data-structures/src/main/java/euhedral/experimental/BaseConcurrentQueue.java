package euhedral.experimental;

import static euhedral.queues.common.QueueUtils.HALF_INCREMENT;
import static euhedral.queues.common.QueueUtils.INCREMENT;
import static euhedral.queues.common.QueueUtils.SHIFT;

import euhedral.queues.common.QueueUtils;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.Consumer;

@SuppressWarnings("unused")
abstract class TailPad extends AbstractConcurrentQueue {

    private long p00, p01, p02, p03, p04, p05, p06, p07;
    private long p08, p09, p10, p11, p12, p13, p14, p15;

    TailPad(Object[] queue) {
        super(queue);
    }
}

@SuppressWarnings("unused")
public abstract class BaseConcurrentQueue extends TailPad {

    private static void linkChunk(Object[] oldQueue, Object[] nextQueue, int cIdx) {
        oldQueue[oldQueue.length - 1] = nextQueue;

        // Publish must happen after link.
        QueueUtils.storeVolatile(oldQueue, cIdx, QueueUtils.SENTINEL);
    }

    protected final long slots;
    protected final long chunkMask;
    protected final int linkIndex;

    protected BaseConcurrentQueue(int chunkSize) {
        super(new Object[(int) roundChunkSize(chunkSize) + 2]);
        this.slots = this.headQueue.length - (SHIFT * 2 + 1);
        this.chunkMask = this.slots << SHIFT;
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

        spSlowStore(obj, tail);
    }

    private void spSlowStore(Object obj, long tail) {
        int cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);

        long head = getHeadAcquire(this);

        long distance = tail - head;
        long diff = this.slots - distance;
        if (diff >= 0) {
            addTailEpochPlain(this, diff << SHIFT);
            storeInTailQueuePlain(this, cIdx, obj);
            setTailRelease(this, tail + INCREMENT);
            return;
        }

        Object[] oldQueue = getTailQueuePlain(this);
        Object[] nextQueue = allocateChunk(oldQueue.length);
        linkChunk(oldQueue, nextQueue, cIdx);

        setTailQueuePlain(this, nextQueue);

        addTailEpochPlain(this, this.slots << SHIFT);

        nextQueue[cIdx] = obj;
        setTailRelease(this, tail + INCREMENT);
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

            // Fastest
            if ((epoch - tail) > 0) {
                if (!casTail(this, tail, tail + INCREMENT)) {
                    continue;
                }
                cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);
                break;
            }
            // Slower
            if (updateTailEpoch(tail, epoch)) {
                if(casTail(this, tail, tail + INCREMENT)) {
                    cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);
                    break;
                }
                continue;
            }

            // Slowest
            if (casTail(this, tail, tail + HALF_INCREMENT)) {
                cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);

                Object[] nextQueue = allocateChunk(queue.length);
                linkChunk(queue, nextQueue, cIdx);

                queue = nextQueue;
                setTailQueuePlain(this, queue);
                setTailEpochPlain(this, tail + this.slots);
                getAndAddTail(this, HALF_INCREMENT);
                break;
            }
        }
        QueueUtils.storeRelease(queue, cIdx, obj);
    }

    private boolean updateTailEpoch(long tail, long epoch) {
        long head = getHeadAcquire(this);

        long distance = tail - head;
        long diff = this.slots - distance;
        if (diff >= 0) {
            long update = diff << SHIFT;
            casTailEpoch(this, epoch, epoch + update);
            return true;
        }
        return false;
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

        long head = getHeadPlain(this);

        // Acquire before touching the queue.
        VarHandle.acquireFence();

        Object[] queue = getHeadQueuePlain(this);

        long total = 0;
        while (total < limit) {
            int cIdx = QueueUtils.chunkIndex(head, this.chunkMask);
            Object obj = queue[cIdx];

            if (obj == null) {
                break;
            }

            if (obj == QueueUtils.SENTINEL) {
                Object[] temp = (Object[]) queue[this.linkIndex];
                queue[cIdx] = null;
                freeChunk(queue);
                queue = temp;
                setHeadQueuePlain(this, queue);
                continue;
            }

            setHeadQueueSlotPlain(this, cIdx, null);
            consumer.accept(obj);
            head += INCREMENT;
            total++;
        }

        VarHandle.releaseFence();

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
