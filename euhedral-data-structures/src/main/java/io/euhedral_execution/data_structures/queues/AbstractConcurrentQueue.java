package io.euhedral_execution.data_structures.queues;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractQueue;

@SuppressWarnings("unused")
abstract class TailPad<T> extends AbstractQueue<T> {
    private long p00, p01, p02, p03, p04, p05, p06, p07;
    private long p08, p09, p10, p11, p12, p13, p14, p15;
}

abstract class TailState<T> extends TailPad<T> {

    protected long tail;

}

@SuppressWarnings("unused")
abstract class MidPad1<T> extends TailState<T> {
    private long p00, p01, p02, p03, p04, p05, p06, p07;
    private long p08, p09, p10, p11, p12, p13, p14, p15;
}

abstract class HeadState<T> extends MidPad1<T> {
    protected long head;
    protected Object[] headQueue;

    HeadState(Object[] queue) {
        this.headQueue = queue;
    }

}

@SuppressWarnings("unused")
abstract class MidPad2<T> extends HeadState<T> {

    private long p00, p01, p02, p03, p04, p05, p06, p07;
    private long p08, p09, p10, p11, p12, p13, p14;

    MidPad2(Object[] queue) {
        super(queue);
    }

}

abstract class TailState2<T> extends MidPad2<T> {
    protected long tailEpoch;
    protected Object[] tailQueue;

    TailState2(Object[] queue) {
        super(queue);
        this.tailQueue = queue;
    }
}

@SuppressWarnings("unused")
abstract class StartPad<T> extends TailState2<T> {
    private long p00, p01, p02, p03, p04, p05, p06, p07;
    private long p08, p09, p10, p11, p12, p13, p14, p15;

    StartPad(Object[] queue) {
        super(queue);
    }
}

@SuppressWarnings({"rawtypes","unused"})
public abstract class AbstractConcurrentQueue<T> extends StartPad<T> {
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
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected AbstractConcurrentQueue(Object[] queue) {
        super(queue);
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

    protected static void setHeadQueueSlotPlain(AbstractConcurrentQueue impl, int cIdx, Object obj) {
        impl.headQueue[cIdx] = obj;
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

    protected static void setTailPlain(AbstractConcurrentQueue impl, long tail) {
        impl.tail = tail;
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

    protected static long caeTail(AbstractConcurrentQueue impl, long current, long next) {
        return (long) TAIL.compareAndExchange(impl, current, next);
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

    protected static void setTailEpochRelease(AbstractConcurrentQueue impl, long epoch) {
        EPOCH.setRelease(impl, epoch);
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
