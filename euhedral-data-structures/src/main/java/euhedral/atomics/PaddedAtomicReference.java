package euhedral.atomics;

import euhedral.atomics.padding.PaddedReference;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Function;

@SuppressWarnings("unchecked")
public final class PaddedAtomicReference<T> extends PaddedReference<T> {

    private static final VarHandle HANDLE;

    static {
        try {
            HANDLE = MethodHandles.lookup()
                    .findVarHandle(PaddedAtomicReference.class, "ref", Object.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    public PaddedAtomicReference() {

    }

    public PaddedAtomicReference(T obj) {
        super.ref = obj;
    }

    // ----- Get -----

    /// Atomic read
    public T get() {
        return (T) HANDLE.getVolatile(this);
    }

    public T getAcquire() {
        return (T) HANDLE.getAcquire(this);
    }

    public T getOpaque() {
        return (T) HANDLE.getOpaque(this);
    }

    public T getPlain() {
        return super.ref;
    }

    public T getAndSet(T obj) {
        return (T) HANDLE.getAndSet(this, obj);
    }

    // ----- Set -----

    /// Atomic set
    public void set(T obj) {
        HANDLE.setVolatile(this, obj);
    }

    public void setRelease(T obj) {
        HANDLE.setRelease(this, obj);
    }

    public void setOpaque(T obj) {
        HANDLE.setOpaque(this, obj);
    }

    public void setPlain(T obj) {
        HANDLE.set(this, obj);
    }

    // ----- CAS -----

    public T getAndUpdate(Function<T, T> updateFunction) {
        T prev, next;
        do {
            prev = get();
            next = updateFunction.apply(prev);
        } while (!HANDLE.weakCompareAndSet(this, prev, next));
        return prev;
    }

    public T updateAndGet(Function<T, T> updateFunction) {
        T prev, next;
        do {
            prev = get();
            next = updateFunction.apply(prev);
        } while (!HANDLE.weakCompareAndSet(this, prev, next));
        return next;
    }

    public boolean compareAndSet(T curr, T next) {
        return HANDLE.compareAndSet(this, curr, next);
    }

    public T compareAndExchange(T curr, T next) {
        return (T) HANDLE.compareAndExchange(this, curr, next);
    }
}
