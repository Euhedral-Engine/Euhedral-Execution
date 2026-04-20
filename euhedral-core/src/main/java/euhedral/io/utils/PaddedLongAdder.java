package euhedral.io.utils;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class PaddedLongAdder {
    private static final int CACHE_LINE_BYTES = 64;
    private static final int LONG_BYTES = 8;
    private static final int STRIDE = CACHE_LINE_BYTES / LONG_BYTES;

    private static final VarHandle HANDLE;

    static {
        HANDLE = MethodHandles.arrayElementVarHandle(long[].class);
    }

    private final long[] counters;
    private final int shift;
    private final int size;

    public PaddedLongAdder(int counters) {
        assert(counters > 0);

        int shift = Integer.numberOfTrailingZeros(STRIDE);
        while((counters << shift) < counters) {
            shift--;
        }
        this.counters = new long[counters << shift];
        this.shift = shift;
        this.size = counters;
    }

    public void increment(int idx) {
        add(idx, 1, false);
    }

    public void atomicIncrement(int idx) {
        add(idx, 1, true);
    }

    public void decrement(int idx) {
        add(idx, -1, false);
    }

    public void atomicDecrement(int idx) {
        add(idx, -1, true);
    }

    public void add(int idx, long value, boolean atomic) {
        if(idx < 0 || idx >= size) {
            throw new ArrayIndexOutOfBoundsException("Size: " + size + " Index: " + idx);
        }

        int shiftedIdx = idx << shift;
        if(atomic) {
            HANDLE.getAndAdd(counters, shiftedIdx, value);
        } else {
            counters[shiftedIdx] += value;
        }
    }

    public int size() {
        return this.size;
    }

    public long sumOpaque() {
        long sum = 0;
        int logical = 0;
        for(int i = 0; i < counters.length; i = ++logical << shift) {
            sum += (long) HANDLE.getOpaque(counters, i);
        }
        return sum;
    }

    public long sum() {
        long sum = 0;
        int logical = 0;
        for(int i = 0; i < counters.length; i = ++logical << shift) {
            sum += (long) HANDLE.getVolatile(counters, i);
        }
        return sum;
    }

    public long sumAndReset() {
        long sum = 0;
        for(int logicalIdx = 0; logicalIdx < size; logicalIdx++) {
            sum += (long) HANDLE.getAndSet(counters, logicalIdx << shift, 0);
        }
        return sum;
    }

    public long decayAndSumDiff(int decayShift, long resetThreshold) {
        long sum = 0;
        for(int logicalIdx = 0; logicalIdx < size; logicalIdx++) {
            int idx = logicalIdx << shift;
            long curr = (long) HANDLE.getAndSet(counters, idx, 0);
            if(curr == 0) {
                continue;
            }
            long next = curr >> shift;
            if(next <= resetThreshold) {
                next = 0;
            } else {
                HANDLE.getAndAdd(counters, idx, next);
            }
            sum += curr - next;
        }

        return sum;
    }

    public void reset() {
        int logical = 0;
        for(int i = 0; i < counters.length; i = ++logical << shift) {
            HANDLE.setVolatile(counters, i, 0);
        }
    }
}
