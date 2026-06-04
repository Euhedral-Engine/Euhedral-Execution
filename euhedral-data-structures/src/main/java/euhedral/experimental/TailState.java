package euhedral.experimental;

import euhedral.atomics.padding.HeadPad;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

abstract class TailHolder extends HeadPad {

    long tail;
}

@SuppressWarnings("unused")
abstract class TailEndPad extends TailHolder {

    private byte b000, b001, b002, b003, b004, b005, b006, b007;
    private byte b008, b009, b010, b011, b012, b013, b014, b015;
    private byte b016, b017, b018, b019, b020, b021, b022, b023;
    private byte b024, b025, b026, b027, b028, b029, b030, b031;
    private byte b032, b033, b034, b035, b036, b037, b038, b039;
    private byte b040, b041, b042, b043, b044, b045, b046, b047;
    private byte b048, b049, b050, b051, b052, b053, b054, b055;
    private byte b056, b057, b058, b059, b060, b061, b062, b063;
    private byte b064, b065, b066, b067, b068, b069, b070, b071;
    private byte b072, b073, b074, b075, b076, b077, b078, b079;
    private byte b080, b081, b082, b083, b084, b085, b086, b087;
    private byte b088, b089, b090, b091, b092, b093, b094, b095;
    private byte b096, b097, b098, b099, b100, b101, b102, b103;
    private byte b104, b105, b106, b107, b108, b109, b110, b111;
    private byte b112, b113, b114, b115, b116, b117, b118, b119;
    private byte b120, b121, b122, b123, b124, b125, b126, b127;
}

abstract class BaseTailState extends TailEndPad {

    long tailEpoch;
    Object[] tailQueue;
    final HeadState head;

    BaseTailState(HeadState head, Object[] queue) {
        Objects.requireNonNull(head);
        Objects.requireNonNull(queue);
        this.tailQueue = queue;
        this.head = head;
    }
}

@SuppressWarnings("unused")
abstract sealed class TailState extends BaseTailState permits MpTailState, SpTailState {

    protected static final VarHandle TAIL;
    protected static final VarHandle EPOCH;
    protected static final VarHandle QUEUE;

    static {
        try {
            TAIL = MethodHandles.lookup().findVarHandle(TailState.class, "tail", long.class);
            EPOCH = MethodHandles.lookup().findVarHandle(TailState.class, "tailEpoch", long.class);
            QUEUE = MethodHandles.lookup().findVarHandle(TailState.class, "tailQueue", Object[].class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    TailState(HeadState head, Object[] queue) {
        super(head, queue);
    }

    final Object[] getTailQueuePlain() {
        return super.tailQueue;
    }

    final Object[] getTailQueueAcquire() {
        return (Object[]) QUEUE.getAcquire(this);
    }

    final void setTailQueuePlain(Object[] queue) {
        super.tailQueue = queue;
    }

    final void storeInTailQueuePlain(int cIdx, Object obj) {
        super.tailQueue[cIdx] = obj;
    }

    // ----- TAIL -----

    public final long getTail() {
        return (long) TAIL.getVolatile(this);
    }

    final long getTailPlain() {
        return super.tail;
    }

    final long getTailAcquire() {
        return (long) TAIL.getAcquire(this);
    }

    final void setTailRelease(long tail) {
        TAIL.setRelease(this, tail);
    }

    final long getAndAddTail(long val) {
        return (long) TAIL.getAndAddRelease(this, val);
    }

    final boolean casTail(long current, long next) {
        return TAIL.compareAndSet(this, current, next);
    }

    // ----- TAIL EPOCH -----

    final long getTailEpochPlain() {
        return super.tailEpoch;
    }

    final long getTailEpochAcquire() {
        return (long) EPOCH.getAcquire(this);
    }

    final void setTailEpochPlain(long epoch) {
        super.tailEpoch = epoch;
    }

    final void addTailEpochPlain(long value) {
        super.tailEpoch += value;
    }

    final boolean casTailEpoch(long current, long next) {
        return EPOCH.compareAndSet(this, current, next);
    }

    final long getHead() {
        return super.head.getHeadAcquire();
    }

}
