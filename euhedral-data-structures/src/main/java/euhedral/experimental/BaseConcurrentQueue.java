package euhedral.experimental;

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
    protected final long slots;
    protected final long chunkMask;
    protected final long shiftedMask;
    protected final int linkIndex;

    protected BaseConcurrentQueue(Object[] queue, long mask) {
        super(queue);
        this.slots = mask + 1;
        this.chunkMask = mask;
        this.shiftedMask = mask << 1;
        this.linkIndex = queue.length - 1;
    }

    // ----- Single Producer -----
    protected void spFill(Object[] objs) {
        for(Object obj : objs) {
            spOffer(obj);
        }
    }

    protected void spFill(Iterable<Object> objs) {
        for(Object obj : objs) {
            spOffer(obj);
        }
    }

    protected final void spOffer(Object obj) {
        Objects.requireNonNull(obj);

        long tail = getTailPlain(this);
        long tailEpoch = getTailEpochPlain(this);

        if (tail < tailEpoch) {
            storeInTailQueuePlain(this, QueueUtils.chunkIndex(tail, this.chunkMask), obj);
            setTailRelease(this, tail + 1);
            return;
        }

        spSlowStore(obj, tail);
    }

    private void spSlowStore(Object obj, long tail) {
        int cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);

        long head = getHeadAcquire(this);

        long diff = QueueUtils.unsignedDiff(head, tail);
        if (diff < this.chunkMask) {
            addTailEpochPlain(this, this.chunkMask - diff);
            storeInTailQueuePlain(this, cIdx, obj);
            setTailRelease(this, tail + 1);
            return;
        }

        Object[] nextQueue = linkChunk(getTailQueuePlain(this), cIdx);
        setTailQueuePlain(this, nextQueue);

        addTailEpochPlain(this, this.chunkMask);

        nextQueue[cIdx] = obj;
        setTailRelease(this, tail + 1);
    }

    // ----- Multi Producer -----

    protected final void mpFill(Object[] objs) {
        for(Object obj : objs) {
            mpOffer(obj);
        }
    }

    protected final void mpFill(Iterable<Object> objs) {
        for(Object obj : objs) {
            mpOffer(obj);
        }
    }

    protected final void mpOffer(Object obj) {
        Objects.requireNonNull(obj);

        int cIdx;
        Object[] queue;
        while(true) {
            long tail = getTailAcquire(this);

            if((tail & 1) == 1) {
                continue;
            }

            queue = getTailQueueAcquire(this);
            long epoch = getTailEpochAcquire(this);
            // Fastest
            if((epoch - tail) > 0) {
                if(!casTail(this, tail, tail + 2)) {
                    continue;
                }
                cIdx = QueueUtils.chunkIndex(tail, this.shiftedMask) >>> 1;
                break;
            }
            // Slower
            if(updateTailEpoch(tail, epoch)) {
                continue;
            }

            // Slowest
            if (casTail(this, tail, tail + 1)) {
                cIdx = QueueUtils.chunkIndex(tail, this.shiftedMask) >>> 1;
                queue = linkChunk(queue, cIdx);
                setTailQueuePlain(this, queue);
                setTailEpochPlain(this, tail + this.shiftedMask - 1);
                getAndAddTail(this, 1);
                break;
            }
        }
        QueueUtils.storeRelease(queue, cIdx, obj);
    }

    private boolean updateTailEpoch(long tail, long epoch) {
        long head = getHeadAcquire(this);
        tail >>>= 1;

        long distance = tail - head;
        long diff = this.slots - distance - 1;
        if(diff > 0) {
            long update = diff << 1;
            casTailEpoch(this, epoch, epoch + update);
            return true;
        }
        return false;
    }

    private static Object[] linkChunk(Object[] oldQueue, int cIdx) {
        Object[] nextQueue = new Object[oldQueue.length];

        oldQueue[oldQueue.length - 1] = nextQueue;

        // Publish must happen after link.
        QueueUtils.storeVolatile(oldQueue, cIdx, QueueUtils.SENTINEL);
        return nextQueue;
    }

    // ----- Single Consumer -----

    protected final Object scPoll() {
        long head = getHeadPlain(this);
        int cIdx = QueueUtils.chunkIndex(head, this.chunkMask);
        Object obj = this.scPeekInternal(cIdx);

        if (obj != null) {
            clearHeadQueueSlotPlain(this, cIdx);
            setHeadRelease(this, head + 1);
        }
        return obj;
    }

    protected final long scDrain(Consumer<Object> consumer, long limit) {
        Objects.requireNonNull(consumer);
        if (limit <= 0) {
            return 0;
        }

        final long headSnapshot = getHeadPlain(this);
        long head = headSnapshot;

        // Acquire before touching the queue.
        VarHandle.acquireFence();

        Object[] queue = getHeadQueuePlain(this);

        long diff = 0;
        while (diff < limit) {
            int cIdx = QueueUtils.chunkIndex(head, this.chunkMask);
            Object obj = queue[cIdx];

            if (obj == null) {
                break;
            }

            if (obj == QueueUtils.SENTINEL) {
                Object[] temp = (Object[]) queue[this.linkIndex];
                if (temp == null) {
                    break;
                }
                queue[this.linkIndex] = null;
                queue = temp;
                setHeadQueuePlain(this, queue);
                continue;
            }

            consumer.accept(obj);
            queue[cIdx] = null;
            head++;
            diff = head - headSnapshot;
        }

        long total = head - headSnapshot;
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
            setHeadQueuePlain(this, temp);

            obj = QueueUtils.loadAcquire(temp, cIdx);
            QueueUtils.storeVolatile(queue, this.linkIndex, null);
        }

        return obj;
    }
}
