package euhedral.atomics;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class AtomicDouble {

    private static final VarHandle HANDLE;

    static {
        try {
            HANDLE = MethodHandles.lookup()
                    .findVarHandle(AtomicDouble.class, "value", double.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }
    
    private double value;

    public AtomicDouble() {

    }

    public AtomicDouble(double value) {
        this.value = value;
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
        return this.value;
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

    public double getAndUpdate(DoubleUnaryOperator updateFunction) {
        double prev, next;
        do {
            prev = get();
            next = updateFunction.applyAsDouble(prev);
        } while (!HANDLE.weakCompareAndSet(this, prev, next));
        return prev;
    }

    public double updateAndGet(DoubleUnaryOperator updateFunction) {
        double prev, next;
        do {
            prev = get();
            next = updateFunction.applyAsDouble(prev);
        } while (!HANDLE.weakCompareAndSet(this, prev, next));
        return next;
    }

    public double getAndAccumulate(double val, DoubleBinaryOperator accumulator) {
        double prev, next;
        do {
            prev = get();
            next = accumulator.applyAsDouble(prev, val);
        } while (!HANDLE.compareAndSet(this, prev, next));
        return prev;
    }

    public double accumulateAndGet(double val, DoubleBinaryOperator accumulator) {
        double prev, next;
        do {
            prev = get();
            next = accumulator.applyAsDouble(prev, val);
        } while (!HANDLE.compareAndSet(this, prev, next));
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

    @FunctionalInterface
    public interface DoubleUnaryOperator {
        double applyAsDouble(double curr);
    }

    @FunctionalInterface
    public interface DoubleBinaryOperator {
        double applyAsDouble(double prev, double curr);
    }
}
