package io.euhedral_execution.data_structures.atomics;

import io.euhedral_execution.data_structures.atomics.padding.PaddedDouble;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.BiFunction;
import java.util.function.Function;

@SuppressWarnings("unused")
public class PaddedAtomicDouble extends PaddedDouble {
    private static final VarHandle HANDLE;

    static {
        try {
            HANDLE = MethodHandles.lookup()
                    .findVarHandle(PaddedAtomicDouble.class, "value", double.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    public PaddedAtomicDouble() {
        super.value = 0d;
    }

    public PaddedAtomicDouble(double value) {
        super.value = value;
    }

    // ----- Get -----

    /// Atomic read
    public double get() {
        return (double) HANDLE.getVolatile(this);
    }

    public double getAcquire() {
        return (double) HANDLE.getAcquire(this);
    }

    public double getOpaque() {
        return (double) HANDLE.getOpaque(this);
    }

    public double getPlain() {
        return super.value;
    }

    public double getAndSet(double val) {
        return (double) HANDLE.getAndSet(this, val);
    }

    // ----- Set -----

    /// Atomic set
    public void set(double val) {
        HANDLE.setVolatile(this, val);
    }

    public void lazySet(double val) {
        setRelease(val);
    }

    public void setRelease(double val) {
        HANDLE.setRelease(this, val);
    }

    public void setOpaque(double val) {
        HANDLE.setOpaque(this, val);
    }

    public void setPlain(double val) {
        HANDLE.set(this, val);
    }

    // ----- RMW -----

    public double getAndIncrement() {
        return getAndAdd(1d);
    }

    public double getAndDecrement() {
        return getAndAdd(-1d);
    }

    public double incrementAndGet() {
        return addAndGet(1d);
    }

    public double decrementAndGet() {
        return addAndGet(-1d);
    }

    public double getAndAdd(double val) {
        return (double) HANDLE.getAndAdd(this, val);
    }

    public double addAndGet(double val) {
        return (double) HANDLE.getAndAdd(this, val) + val;
    }

    // ----- CAS -----

    public double getAndUpdate(Function<Double, Double> updateFunction) {
        double prev, next;
        do {
            prev = get();
            next = updateFunction.apply(prev);
        } while (!weakCompareAndSet(prev, next));
        return prev;
    }

    public double updateAndGet(Function<Double, Double> updateFunction) {
        double prev, next;
        do {
            prev = get();
            next = updateFunction.apply(prev);
        } while (!weakCompareAndSet(prev, next));
        return next;
    }

    public double getAndAccumulate(double val, BiFunction<Double, Double, Double> accumulator) {
        double prev, next;
        do {
            prev = get();
            next = accumulator.apply(prev, val);
        } while (!compareAndSet(prev, next));
        return prev;
    }

    public double accumulateAndGet(double val, BiFunction<Double, Double, Double> accumulator) {
        double prev, next;
        do {
            prev = get();
            next = accumulator.apply(prev, val);
        } while (!compareAndSet(prev, next));
        return next;
    }

    public boolean compareAndSet(double curr, double next) {
        return HANDLE.compareAndSet(this, curr, next);
    }

    public boolean weakCompareAndSet(double curr, double next) {
        return HANDLE.weakCompareAndSet(this, curr, next);
    }

    public double compareAndExchange(double curr, double next) {
        return (double) HANDLE.compareAndExchange(this, curr, next);
    }
}
