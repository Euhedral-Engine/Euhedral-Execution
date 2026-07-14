package io.euhedral_execution.data_structures.atomics;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NonNull;

@SuppressWarnings({"unchecked", "unused"})
public final class PaddedAtomicReferenceArray<T> {

    public static final int MAX_PADDING;
    private static final VarHandle HANDLE = MethodHandles.arrayElementVarHandle(Object[].class);

    static {
        int ptr;
        if (System.getProperty("sun.arch.data.model").contains("32")) {
            ptr = 4;
        } else {
            try {
                HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(
                        HotSpotDiagnosticMXBean.class);
                String useCompressedOops = bean.getVMOption("UseCompressedOops").getValue();
                ptr = "true".equals(useCompressedOops) ? 4 : 8;
            } catch (Exception e) {
                ptr = 8;
            }
        }

        MAX_PADDING = (64 / ptr) - 1;
    }

    private final int padding;
    private final T[] array;
    private final int length;
    private final boolean boundsCheck;
    private final boolean pow2;

    public PaddedAtomicReferenceArray(int length) {
        this(length, true, false);
    }

    public PaddedAtomicReferenceArray(int length, boolean boundsCheck, boolean pad128) {
        int padding = pad128 ? MAX_PADDING * 2 + 2 : MAX_PADDING + 1;

        long padded;
        do {
            padding--;
            padded = (length + 1L) * padding + length;
        } while (padded > Integer.MAX_VALUE);

        this.padding = padding;
        this.array = (T[]) new Object[(int) padded];
        this.length = length;
        this.boundsCheck = boundsCheck;
        this.pow2 = Integer.highestOneBit(length) == length;
    }

    // ----- Get -----

    /// Atomic read
    public T get(int idx) {
        return (T) HANDLE.getVolatile(this.array, getPhysicalIdx(idx));
    }

    public T getAcquire(int idx) {
        return (T) HANDLE.getAcquire(this.array, getPhysicalIdx(idx));
    }

    public T getOpaque(int idx) {
        return (T) HANDLE.getOpaque(this.array, getPhysicalIdx(idx));
    }

    public T getPlain(int idx) {
        return this.array[getPhysicalIdx(idx)];
    }

    public T getAndSet(int idx, T obj) {
        return (T) HANDLE.getAndSet(this.array, getPhysicalIdx(idx), obj);
    }

    // ----- Set -----

    /// Atomic set
    public void set(int idx, T obj) {
        HANDLE.setVolatile(this.array, getPhysicalIdx(idx), obj);
    }

    public void setRelease(int idx, T obj) {
        HANDLE.setRelease(this.array, getPhysicalIdx(idx), obj);
    }

    public void setOpaque(int idx, T obj) {
        HANDLE.setOpaque(this.array, getPhysicalIdx(idx), obj);
    }

    public void setPlain(int idx, T obj) {
        this.array[getPhysicalIdx(idx)] = obj;
    }

    /// Atomic
    public void fill(T obj) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            HANDLE.setVolatile(this.array, pIdx, obj);
        }
    }

    public void lazyFill(T obj) {
        fillRelease(obj);
    }

    public void fillRelease(T obj) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            HANDLE.setRelease(this.array, pIdx, obj);
        }
    }

    public void fillOpaque(T obj) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            HANDLE.setOpaque(this.array, pIdx, obj);
        }
    }

    public void fillPlain(T obj) {
        for (int i = 0; i < length; i++) {
            int pIdx = ((i + 1) * this.padding) + i;
            this.array[pIdx] = obj;
        }
    }

    // ----- CAS -----

    public T getAndUpdate(int idx, @NonNull UnaryOperator<T> updateFunction) {
        int pIdx = getPhysicalIdx(idx);
        T prev, next;
        do {
            prev = (T) HANDLE.getAcquire(this.array, pIdx);
            next = updateFunction.apply(prev);
        } while (!HANDLE.weakCompareAndSet(this.array, pIdx, prev, next));
        return prev;
    }

    public T updateAndGet(int idx, @NonNull UnaryOperator<T> updateFunction) {
        int pIdx = getPhysicalIdx(idx);
        T prev, next;
        do {
            prev = (T) HANDLE.getAcquire(this.array, pIdx);
            next = updateFunction.apply(prev);
        } while (!HANDLE.weakCompareAndSet(this.array, pIdx, prev, next));
        return next;
    }

    public boolean compareAndSet(int idx, T expect, T update) {
        return HANDLE.compareAndSet(this.array, getPhysicalIdx(idx), expect, update);
    }

    public T compareAndExchange(int idx, T expect, T update) {
        return (T) HANDLE.compareAndExchange(this.array, getPhysicalIdx(idx), expect, update);
    }

    public boolean weakCompareAndSet(int idx, T expect, T update) {
        return HANDLE.weakCompareAndSet(this.array, getPhysicalIdx(idx), expect, update);
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
        return Arrays.toString(this.array);
    }
}
