package io.euhedral_execution.data_structures.atomics;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;
import org.jspecify.annotations.NonNull;

@SuppressWarnings("unused")
public class PaddedAtomicLongArray {

    private static final VarHandle HANDLE = MethodHandles.arrayElementVarHandle(long[].class);

    private static final int MAX_PADDING = 7;

    protected final int padding;
    protected final long[] array;
    protected final boolean pow2;
    protected final boolean boundsCheck;
    private final int length;

    public PaddedAtomicLongArray(int length) {
        this(length, true, false);
    }

    public PaddedAtomicLongArray(int length, boolean boundsCheck, boolean pad128) {
        int padding = pad128 ? MAX_PADDING * 2 + 2 : MAX_PADDING + 1;

        long padded;
        do {
            padding--;
            padded = (length + 1L) * padding + length;
        } while (padded > Integer.MAX_VALUE);

        this.padding = padding;
        this.array = new long[(int) padded];
        this.length = length;
        this.boundsCheck = boundsCheck;
        this.pow2 = Integer.highestOneBit(length) == length;
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
        this.array[getPhysicalIdx(idx)] = val;
    }

    /// Atomic
    public void fill(long val) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            HANDLE.setVolatile(this.array, pIdx, val);
        }
    }

    public void lazyFill(long val) {
        fillRelease(val);
    }

    public void fillRelease(long val) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            HANDLE.setRelease(this.array, pIdx, val);
        }
    }

    public void fillOpaque(long val) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            HANDLE.setOpaque(this.array, pIdx, val);
        }
    }

    public void fillPlain(long val) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            this.array[pIdx] = val;
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
        return getAndAdd(idx, val) + val;
    }

    public long getAndAddRelease(int idx, long val) {
        return (long) HANDLE.getAndAddRelease(this.array, getPhysicalIdx(idx), val);
    }

    public long addReleaseAndGet(int idx, long val) {
        return getAndAddRelease(idx, val) + val;
    }

    // ----- CAS -----

    public long getAndUpdate(int idx, @NonNull LongUnaryOperator updateFunction) {
        int pIdx = getPhysicalIdx(idx);
        long prev, next;
        do {
            prev = (long) HANDLE.getAcquire(this.array, pIdx);
            next = updateFunction.applyAsLong(prev);
        } while (!HANDLE.weakCompareAndSet(this.array, pIdx, prev, next));
        return prev;
    }

    public long updateAndGet(int idx, @NonNull LongUnaryOperator updateFunction) {
        int pIdx = getPhysicalIdx(idx);
        long prev, next;
        do {
            prev = (long) HANDLE.getAcquire(this.array, pIdx);
            next = updateFunction.applyAsLong(prev);
        } while (!HANDLE.weakCompareAndSet(this.array, pIdx, prev, next));
        return next;
    }

    public long getAndAccumulate(int idx, long val, @NonNull LongBinaryOperator accumulator) {
        int pIdx = getPhysicalIdx(idx);
        long prev, next;
        do {
            prev = (long) HANDLE.getAcquire(this.array, pIdx);
            next = accumulator.applyAsLong(prev, val);
        } while (!HANDLE.compareAndSet(this.array, pIdx, prev, next));
        return prev;
    }

    public long accumulateAndGet(int idx, long val, @NonNull LongBinaryOperator accumulator) {
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

    public int fromRawIdx(long rawIdx) {
        int logical;
        if (pow2) {
            logical = (int) (rawIdx & (length - 1));
        } else {
            logical = Math.floorMod((int) rawIdx, this.length);
        }
        return ((logical + 1) * this.padding) + logical;
    }

    private int getPhysicalIdx(int idx) {
        if (!this.boundsCheck) {
            return idx;
        }
        boundsCheck(idx);
        return ((idx + 1) * this.padding) + idx;
    }

    private void boundsCheck(int idx) {
        if (idx < 0 || idx >= this.length) {
            throw new IndexOutOfBoundsException("Index " + idx + " out of bounds for length " + length);
        }
    }

    public int length() {
        return this.length;
    }

    @Override
    public String toString() {
        if (this.boundsCheck) {
            StringJoiner sj = new StringJoiner(", ", "[", "]");
            for (int i = 0; i < this.length; i++) {
                sj.add(Double.toString(this.array[((i + 1) * this.padding) + i]));
            }
            VarHandle.acquireFence();
            return sj.toString();
        }
        String string = Arrays.toString(this.array);
        VarHandle.acquireFence();
        return string;
    }
}
