package euhedral.experimental;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

@SuppressWarnings("unused")
abstract class HeadPad {
    private long p00, p01, p02, p03, p04, p05, p06, p07;
    private long p08, p09, p10, p11, p12, p13, p14, p15;
}

abstract class HeadState extends HeadPad {
    protected long head;
    protected Object[] headQueue;

    HeadState(Object[] queue) {
        this.headQueue = queue;
    }
}

@SuppressWarnings("unused")
abstract class MidPad extends HeadState {

    private long p00, p01, p02, p03, p04, p05, p06, p07;
    private long p08, p09, p10, p11, p12, p13, p14, p15;

    MidPad(Object[] queue) {
        super(queue);
    }
}

abstract class TailState extends MidPad {

    protected long tail;
    protected long tailEpoch;
    protected Object[] tailQueue;

    TailState(Object[] queue) {
        super(queue);
        this.tailQueue = queue;
    }
}

@SuppressWarnings("unused")
public abstract class AbstractConcurrentQueue extends TailState {
    protected static final VarHandle HEAD;
    protected static final VarHandle TAIL;
    protected static final VarHandle EPOCH;
    protected static final VarHandle TAIL_QUEUE;

    static {
        try {
            HEAD = MethodHandles.lookup().findVarHandle(AbstractConcurrentQueue.class, "head", long.class);
            TAIL = MethodHandles.lookup().findVarHandle(AbstractConcurrentQueue.class, "tail", long.class);
            EPOCH = MethodHandles.lookup().findVarHandle(AbstractConcurrentQueue.class, "tailEpoch", long.class);
            TAIL_QUEUE = MethodHandles.lookup().findVarHandle(AbstractConcurrentQueue.class, "tailQueue", Object[].class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    AbstractConcurrentQueue(Object[] queue) {
        super(queue);
    }

    protected static long roundChunkSize(long chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }

        return Long.highestOneBit((chunkSize - 1) << 1);
    }

    // ----- HEAD -----

    protected static long getHeadPlain(AbstractConcurrentQueue impl) {
        return impl.head;
    }

    protected static long getHeadAcquire(AbstractConcurrentQueue impl) {
        return (long) HEAD.getAcquire(impl);
    }

    protected static void setHeadRelease(AbstractConcurrentQueue impl, long head) {
        HEAD.setRelease(impl, head);
    }

    // ----- HEAD QUEUE -----
    protected static Object[] getHeadQueuePlain(AbstractConcurrentQueue impl) {
        return impl.headQueue;
    }

    protected static void setHeadQueuePlain(AbstractConcurrentQueue impl, Object[] queue) {
        impl.headQueue = queue;
    }

    protected static void clearHeadQueueSlotPlain(AbstractConcurrentQueue impl, int cIdx) {
        impl.headQueue[cIdx] = null;
    }

    // ----- TAIL -----

    protected static long getTail(AbstractConcurrentQueue impl) {
        return (long) TAIL.getVolatile(impl);
    }

    protected static long getTailPlain(AbstractConcurrentQueue impl) {
        return impl.tail;
    }

    protected static long getTailAcquire(AbstractConcurrentQueue impl) {
        return (long) TAIL.getAcquire(impl);
    }

    protected static void setTailRelease(AbstractConcurrentQueue impl, long tail) {
        TAIL.setRelease(impl, tail);
    }

    protected static long getAndAddTail(AbstractConcurrentQueue impl, long val) {
        return (long) TAIL.getAndAddRelease(impl, val);
    }

    protected static boolean casTail(AbstractConcurrentQueue impl, long current, long next) {
        return TAIL.compareAndSet(impl, current, next);
    }

    // ----- TAIL EPOCH -----

    protected static long getTailEpochPlain(AbstractConcurrentQueue impl) {
        return impl.tailEpoch;
    }

    protected static long getTailEpochAcquire(AbstractConcurrentQueue impl) {
        return (long) EPOCH.getAcquire(impl);
    }

    protected static void setTailEpochPlain(AbstractConcurrentQueue impl, long epoch) {
        impl.tailEpoch = epoch;
    }

    protected static void addTailEpochPlain(AbstractConcurrentQueue impl, long value) {
        impl.tailEpoch += value;
    }

    protected static boolean casTailEpoch(AbstractConcurrentQueue impl, long current, long next) {
        return EPOCH.compareAndSet(impl, current, next);
    }

    // ----- TAIL QUEUE -----

    protected static Object[] getTailQueuePlain(AbstractConcurrentQueue impl) {
        return impl.tailQueue;
    }

    protected static Object[] getTailQueueAcquire(AbstractConcurrentQueue impl) {
        return (Object[]) TAIL_QUEUE.getAcquire(impl);
    }

    protected static void setTailQueuePlain(AbstractConcurrentQueue impl, Object[] queue) {
        impl.tailQueue = queue;
    }

    protected static void storeInTailQueuePlain(AbstractConcurrentQueue impl, int cIdx, Object obj) {
        impl.tailQueue[cIdx] = obj;
    }
}
