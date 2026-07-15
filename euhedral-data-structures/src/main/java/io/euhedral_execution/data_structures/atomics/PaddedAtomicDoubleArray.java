package io.euhedral_execution.data_structures.atomics;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

import org.jspecify.annotations.NonNull;

@SuppressWarnings("unused")
public sealed class PaddedAtomicDoubleArray permits PaddedDoubleAdder {
    private static final VarHandle HANDLE = MethodHandles.arrayElementVarHandle(double[].class);

    private static final int MAX_PADDING = 7;

    protected final double[] array;
    protected final boolean boundsCheck;
    protected final int padding;

    private final int length;
    private final boolean pow2;

    public PaddedAtomicDoubleArray(int length) {
        this(length, true, false);
    }

    public PaddedAtomicDoubleArray(int length, boolean boundsCheck, boolean pad128) {
        int padding = pad128 ? MAX_PADDING * 2 + 2 : MAX_PADDING + 1;

        double padded;
        do {
            padding--;
            padded = (length + 1.0) * padding + length;
        } while (padded > Integer.MAX_VALUE);

        this.padding = padding;
        this.array = new double[(int) padded];
        this.length = length;
        this.boundsCheck = boundsCheck;
        this.pow2 = Integer.highestOneBit(length) == length;
    }

    // ----- Get -----

    /// Atomic read
    public double get(int idx) {
        return (double) HANDLE.getVolatile(this.array, getPhysicalIdx(idx));
    }

    public double getAcquire(int idx) {
        return (double) HANDLE.getAcquire(this.array, getPhysicalIdx(idx));
    }

    public double getOpaque(int idx) {
        return (double) HANDLE.getOpaque(this.array, getPhysicalIdx(idx));
    }

    public double getPlain(int idx) {
        return this.array[getPhysicalIdx(idx)];
    }

    public double getAndSet(int idx, double val) {
        return (double) HANDLE.getAndSet(this.array, getPhysicalIdx(idx), val);
    }

    // ----- Set -----

    /// Atomic set
    public void set(int idx, double val) {
        HANDLE.setVolatile(this.array, getPhysicalIdx(idx), val);
    }

    public void lazySet(int idx, double val) {
        setRelease(idx, val);
    }

    public void setRelease(int idx, double val) {
        HANDLE.setRelease(this.array, getPhysicalIdx(idx), val);
    }

    public void setOpaque(int idx, double val) {
        HANDLE.setOpaque(this.array, getPhysicalIdx(idx), val);
    }

    public void setPlain(int idx, double val) {
        this.array[getPhysicalIdx(idx)] = val;
    }

    /// Atomic
    public void fill(double val) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            HANDLE.setVolatile(this.array, pIdx, val);
        }
    }

    public void lazyFill(double val) {
        fillRelease(val);
    }

    public void fillRelease(double val) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            HANDLE.setRelease(this.array, pIdx, val);
        }
    }

    public void fillOpaque(double val) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            HANDLE.setOpaque(this.array, pIdx, val);
        }
    }

    public void fillPlain(double val) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            this.array[pIdx] = val;
        }
    }

    // ----- RMW -----

    public double getAndIncrement(int idx) {
        return getAndAdd(idx, 1d);
    }

    public double getAndDecrement(int idx) {
        return getAndAdd(idx, -1d);
    }

    public double incrementAndGet(int idx) {
        return addAndGet(idx, 1d);
    }

    public double decrementAndGet(int idx) {
        return addAndGet(idx, -1d);
    }

    public double getAndAdd(int idx, double val) {
        return (double) HANDLE.getAndAdd(this.array, getPhysicalIdx(idx), val);
    }

    public double addAndGet(int idx, double val) {
        return (double) HANDLE.getAndAdd(this.array, getPhysicalIdx(idx), val) + val;
    }

    public double getAndAddRelease(int idx, double val) {
        return (double) HANDLE.getAndAddRelease(this.array, getPhysicalIdx(idx), val);
    }

    public double addReleaseAndGet(int idx, double val) {
        return getAndAddRelease(idx, val) + val;
    }

    // ----- CAS -----

    public double getAndUpdate(int idx, @NonNull DoubleUnaryOperator updateFunction) {
        int pIdx = getPhysicalIdx(idx);
        double prev, next;
        do {
            prev = (double) HANDLE.getAcquire(this.array, pIdx);
            next = updateFunction.applyAsDouble(prev);
        } while (!HANDLE.weakCompareAndSet(this.array, pIdx, prev, next));
        return prev;
    }

    public double updateAndGet(int idx, @NonNull DoubleUnaryOperator updateFunction) {
        int pIdx = getPhysicalIdx(idx);
        double prev, next;
        do {
            prev = (double) HANDLE.getAcquire(this.array, pIdx);
            next = updateFunction.applyAsDouble(prev);
        } while (!HANDLE.weakCompareAndSet(this.array, pIdx, prev, next));
        return next;
    }

    public double getAndAccumulate(int idx, double val, @NonNull DoubleBinaryOperator accumulator) {
        int pIdx = getPhysicalIdx(idx);
        double prev, next;
        do {
            prev = (double) HANDLE.getAcquire(this.array, pIdx);
            next = accumulator.applyAsDouble(prev, val);
        } while (!HANDLE.compareAndSet(this.array, pIdx, prev, next));
        return prev;
    }

    public double accumulateAndGet(int idx, double val, @NonNull DoubleBinaryOperator accumulator) {
        int pIdx = getPhysicalIdx(idx);
        double prev, next;
        do {
            prev = (double) HANDLE.getAcquire(this.array, pIdx);
            next = accumulator.applyAsDouble(prev, val);
        } while (!HANDLE.compareAndSet(this.array, pIdx, prev, next));
        return next;
    }

    public boolean compareAndSet(int idx, double expect, double update) {
        return HANDLE.compareAndSet(this.array, getPhysicalIdx(idx), expect, update);
    }

    public boolean weakCompareAndSet(int idx, double expect, double update) {
        return HANDLE.weakCompareAndSet(this.array, getPhysicalIdx(idx), expect, update);
    }

    public double compareAndExchange(int idx, double expect, double update) {
        return (double) HANDLE.compareAndExchange(this.array, getPhysicalIdx(idx), expect, update);
    }

    public int fromRawIdx(long rawIdx) {
        int logical;
        if(pow2) {
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
            throw new IndexOutOfBoundsException(
                    "Index " + idx + " out of bounds for length " + length);
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
