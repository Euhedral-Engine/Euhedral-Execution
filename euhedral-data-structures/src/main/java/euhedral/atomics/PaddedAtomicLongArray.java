package euhedral.atomics;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

@SuppressWarnings("unused")
public final class PaddedAtomicLongArray {

    private static final VarHandle HANDLE = MethodHandles.arrayElementVarHandle(long[].class);

    private static final int PADDING = 7;

    private final int padding;
    private final long[] array;
    private final int length;
    private final boolean boundsCheck;

    public PaddedAtomicLongArray(int length) {
        this(length, true, false);
    }

    public PaddedAtomicLongArray(int length, boolean boundsCheck, boolean pad128) {
        int padding = pad128 ? PADDING * 2 + 1 : PADDING;

        long padded;
        do {
            padding--;
            padded = (length + 1L) * padding + length;
        } while (padded > Integer.MAX_VALUE);

        this.padding = padding;
        this.array = new long[(int) padded];
        this.length = length;
        this.boundsCheck = boundsCheck;
    }

    // ----- Get -----

    /// Atomic read
    public long get(int idx) {
        return (long) HANDLE.getVolatile(this.array, getPhysicalIdx(idx));
    }

    public long getAcquire(int idx) {
        return (long) HANDLE.getAcquire(this.array, getPhysicalIdx(idx));
    }

    public long getOpaque(int idx) {
        return (long) HANDLE.getOpaque(this.array, getPhysicalIdx(idx));
    }

    public long getPlain(int idx) {
        return this.array[getPhysicalIdx(idx)];
    }

    public long getAndSet(int idx, long val) {
        return (long) HANDLE.getAndSet(this.array, getPhysicalIdx(idx), val);
    }

    // ----- Set -----

    /// Atomic set
    public void set(int idx, long val) {
        HANDLE.setVolatile(this.array, getPhysicalIdx(idx), val);
    }

    public void lazySet(int idx, long val) {
        setRelease(idx, val);
    }

    public void setRelease(int idx, long val) {
        HANDLE.setRelease(this.array, getPhysicalIdx(idx), val);
    }

    public void setOpaque(int idx, long val) {
        HANDLE.setOpaque(this.array, getPhysicalIdx(idx), val);
    }

    public void setPlain(int idx, long val) {
        HANDLE.set(this.array, getPhysicalIdx(idx), val);
    }

    /// Atomic
    public void fill(long val) {
        for (int i = 0; i < length; i++) {
            set(i, val);
        }
    }

    public void lazyFill(long val) {
        fillRelease(val);
    }

    public void fillRelease(long val) {
        for (int i = 0; i < length; i++) {
            setRelease(i, val);
        }
    }

    public void fillOpaque(long val) {
        for (int i = 0; i < length; i++) {
            setOpaque(i, val);
        }
    }

    public void fillPlain(long val) {
        for (int i = 0; i < length; i++) {
            setPlain(i, val);
        }
    }

    // ----- RMW -----

    public long getAndIncrement(int idx) {
        return getAndAdd(idx, 1L);
    }

    public long getAndDecrement(int idx) {
        return getAndAdd(idx, -1L);
    }

    public long incrementAndGet(int idx) {
        return addAndGet(idx, 1L);
    }

    public long decrementAndGet(int idx) {
        return addAndGet(idx, -1L);
    }

    public long getAndAdd(int idx, long val) {
        return (long) HANDLE.getAndAdd(this.array, getPhysicalIdx(idx), val);
    }

    public long addAndGet(int idx, long val) {
        return (long) HANDLE.getAndAdd(this.array, getPhysicalIdx(idx), val) + val;
    }

    // ----- CAS -----

    public long getAndUpdate(int idx, LongUnaryOperator updateFunction) {
        int pIdx = getPhysicalIdx(idx);
        long prev, next;
        do {
            prev = (long) HANDLE.getAcquire(this.array, pIdx);
            next = updateFunction.applyAsLong(prev);
        } while (!HANDLE.weakCompareAndSet(this.array, pIdx, prev, next));
        return prev;
    }

    public long updateAndGet(int idx, LongUnaryOperator updateFunction) {
        int pIdx = getPhysicalIdx(idx);
        long prev, next;
        do {
            prev = (long) HANDLE.getAcquire(this.array, pIdx);
            next = updateFunction.applyAsLong(prev);
        } while (!HANDLE.weakCompareAndSet(this.array, pIdx, prev, next));
        return next;
    }

    public long getAndAccumulate(int idx, long val, LongBinaryOperator accumulator) {
        int pIdx = getPhysicalIdx(idx);
        long prev, next;
        do {
            prev = (long) HANDLE.getAcquire(this.array, pIdx);
            next = accumulator.applyAsLong(prev, val);
        } while (!HANDLE.compareAndSet(this.array, pIdx, prev, next));
        return prev;
    }

    public long accumulateAndGet(int idx, long val, LongBinaryOperator accumulator) {
        int pIdx = getPhysicalIdx(idx);
        long prev, next;
        do {
            prev = (long) HANDLE.getAcquire(this.array, pIdx);
            next = accumulator.applyAsLong(prev, val);
        } while (!HANDLE.compareAndSet(this.array, pIdx, prev, next));
        return next;
    }

    public boolean compareAndSet(int idx, long expect, long update) {
        return HANDLE.compareAndSet(this.array, getPhysicalIdx(idx), expect, update);
    }

    public boolean weakCompareAndSet(int idx, long expect, long update) {
        return HANDLE.weakCompareAndSet(this.array, getPhysicalIdx(idx), expect, update);
    }

    public long compareAndExchange(int idx, long expect, long update) {
        return (long) HANDLE.compareAndExchange(this.array, getPhysicalIdx(idx), expect, update);
    }

    public int getPhysicalIdx(int idx) {
        if (this.boundsCheck) {
            boundsCheck(idx);
        }
        return ((idx + 1) * this.padding) + idx;
    }

    private void boundsCheck(int idx) {
        if (idx < 0 || idx >= this.length) {
            throw new IndexOutOfBoundsException(
                    "Index " + idx + " out of bounds for length " + length);
        }
    }

    public int length() {
        return this.length;
    }

    @Override
    public String toString() {
        return Arrays.toString(this.array);
    }
}
