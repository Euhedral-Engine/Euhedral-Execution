package io.euhedral_execution.data_structures.atomics;

import io.euhedral_execution.data_structures.atomics.padding.PaddedLong;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

@SuppressWarnings("unused")
public class PaddedAtomicLong extends PaddedLong {
    private static final VarHandle HANDLE;

    static {
        try {
            HANDLE = MethodHandles.lookup().findVarHandle(PaddedAtomicLong.class, "value", long.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public PaddedAtomicLong() {
        super.value = 0L;
    }

    public PaddedAtomicLong(long value) {
        super.value = value;
    }

    // ----- Get -----

    /// Atomic read
    public long get() {
        return (long) HANDLE.getVolatile(this);
    }

    public long getAcquire() {
        return (long) HANDLE.getAcquire(this);
    }

    public long getOpaque() {
        return (long) HANDLE.getOpaque(this);
    }

    public void setOpaque(long val) {
        HANDLE.setOpaque(this, val);
    }

    public long getPlain() {
        return super.value;
    }

    // ----- Set -----

    public void setPlain(long val) {
        super.value = val;
    }

    public long getAndSet(long val) {
        return (long) HANDLE.getAndSet(this, val);
    }

    /// Atomic set
    public void set(long val) {
        HANDLE.setVolatile(this, val);
    }

    public void lazySet(long val) {
        setRelease(val);
    }

    public void setRelease(long val) {
        HANDLE.setRelease(this, val);
    }

    // ----- RMW -----

    public long getAndIncrement() {
        return getAndAdd(1L);
    }

    public long getAndDecrement() {
        return getAndAdd(-1L);
    }

    public long incrementAndGet() {
        return addAndGet(1L);
    }

    public long decrementAndGet() {
        return addAndGet(-1L);
    }

    public long getAndAdd(long val) {
        return (long) HANDLE.getAndAdd(this, val);
    }

    public long getAndAddRelease(long val) {
        return (long) HANDLE.getAndAddRelease(this, val);
    }

    public long addAndGet(long val) {
        return (long) HANDLE.getAndAdd(this, val) + val;
    }

    // ----- CAS -----

    public long getAndUpdate(LongUnaryOperator updateFunction) {
        long prev, next;
        do {
            prev = get();
            next = updateFunction.applyAsLong(prev);
        } while (!HANDLE.weakCompareAndSet(this, prev, next));
        return prev;
    }

    public long updateAndGet(LongUnaryOperator updateFunction) {
        long prev, next;
        do {
            prev = get();
            next = updateFunction.applyAsLong(prev);
        } while (!HANDLE.weakCompareAndSet(this, prev, next));
        return next;
    }

    public long getAndAccumulate(long val, LongBinaryOperator accumulator) {
        long prev, next;
        do {
            prev = get();
            next = accumulator.applyAsLong(prev, val);
        } while (!HANDLE.compareAndSet(this, prev, next));
        return prev;
    }

    public long accumulateAndGet(long val, LongBinaryOperator accumulator) {
        long prev, next;
        do {
            prev = get();
            next = accumulator.applyAsLong(prev, val);
        } while (!HANDLE.compareAndSet(this, prev, next));
        return next;
    }

    public boolean compareAndSet(long curr, long next) {
        return HANDLE.compareAndSet(this, curr, next);
    }

    public boolean weakCompareAndSet(long curr, long next) {
        return HANDLE.weakCompareAndSet(this, curr, next);
    }

    public long compareAndExchange(long curr, long next) {
        return (long) HANDLE.compareAndExchange(this, curr, next);
    }
}
