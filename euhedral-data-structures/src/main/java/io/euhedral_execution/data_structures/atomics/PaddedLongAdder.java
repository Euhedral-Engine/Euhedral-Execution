package io.euhedral_execution.data_structures.atomics;

import java.lang.invoke.VarHandle;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("unused")
public final class PaddedLongAdder extends PaddedAtomicLongArray {

    private static final long SIGN_BIT = 1L << 63;

    public PaddedLongAdder() {
        super(Runtime.getRuntime().availableProcessors(), true, false);
    }

    public PaddedLongAdder(int length) {
        super(length, true, false);
    }

    public PaddedLongAdder(int length, boolean boundsCheck, boolean pad128) {
        super(length, boundsCheck, pad128);
    }

    public void increment() {
        long rand = ThreadLocalRandom.current().nextLong();
        super.getAndAddRelease(super.fromRawIdx(rand), 1);
    }

    public void increment(int idx) {
        super.getAndAddRelease(idx, 1);
    }

    public void decrement() {
        long rand = ThreadLocalRandom.current().nextLong();
        super.getAndAddRelease(super.fromRawIdx(rand), -1);
    }

    public void decrement(int idx) {
        super.getAndAddRelease(idx, -1);
    }

    public void add(long value) {
        long rand = ThreadLocalRandom.current().nextLong();
        super.getAndAddRelease(super.fromRawIdx(rand), value);
    }

    public void add(int idx, long value) {
        super.getAndAddRelease(idx, value);
    }

    public long min() {
        long min = Long.MAX_VALUE;
        if (boundsCheck) {
            for (int i = 0; i < super.length(); i++) {
                min = Math.min(min, super.array[((i + 1) * this.padding) + i]);
            }
            VarHandle.acquireFence();
        } else {
            for (long l : super.array) {
                min = Math.min(min, l);
            }
            VarHandle.acquireFence();
        }
        return min;
    }

    public long max() {
        long max = Long.MIN_VALUE;
        if (boundsCheck) {
            for (int i = 0; i < super.length(); i++) {
                max = Math.max(max, super.array[((i + 1) * this.padding) + i]);
            }
            VarHandle.acquireFence();
        } else {
            for (long l : super.array) {
                max = Math.max(max, l);
            }
            VarHandle.acquireFence();
        }
        return max;
    }

    public double mean() {
        int count = 0;

        long upper = 0;
        long lower = 0;

        if (boundsCheck) {
            for (int i = 0; i < super.length(); i++) {
                count++;

                long valLower = super.array[((i + 1) * this.padding) + i];
                long valUpper = valLower < 0 ? -1L : 0;

                long lowerResult = lower + valLower;
                long carry = (Long.compareUnsigned(lowerResult, lower) < 0) ? 1L : 0L;
                upper += valUpper + carry;
                lower = lowerResult;
            }
            VarHandle.acquireFence();
        } else {
            for (long valLower : super.array) {
                count++;

                long valUpper = valLower < 0 ? -1L : 0;

                long lowerResult = lower + valLower;
                long carry = (Long.compareUnsigned(lowerResult, lower) < 0) ? 1L : 0L;
                upper += valUpper + carry;
                lower = lowerResult;
            }
            VarHandle.acquireFence();
        }

        if (count == 0) {
            return 0;
        }

        long absUpper = upper;
        long absLower = lower;

        boolean negative = upper < 0;
        if (negative) {
            absLower = ~lower + 1;
            absUpper = ~upper + (absLower == 0 ? 1 : 0);
        }

        double sumAsDouble;
        if (absUpper == 0) {
            sumAsDouble = (double) (absLower >>> 1) * 2.0 + (absLower & 1);
        } else {
            double upperScaled = (double) absUpper * 18446744073709551616.0; // 2^64
            double lowerUnsigned = (double) (absLower >>> 1) * 2.0 + (absLower & 1);
            sumAsDouble = upperScaled + lowerUnsigned;
        }
        sumAsDouble *= negative ? -1.0 : 1.0;
        return sumAsDouble / count;
    }

    public long sum() {
        long sum = 0;
        if (boundsCheck) {
            for (int i = 0; i < super.length(); i++) {
                sum += super.array[((i + 1) * this.padding) + i];
            }
            VarHandle.acquireFence();
        } else {
            for (long l : super.array) {
                sum += l;
            }
            VarHandle.acquireFence();
        }
        return sum;
    }

    public long sumAndReset() {
        long sum = 0;
        if (boundsCheck) {
            for (int i = 0; i < super.length(); i++) {
                sum += super.getAndSet(i, 0);
            }
        } else {
            for (int i = 0; i < super.array.length; i++) {
                sum += super.getAndSet(i, 0);
            }
        }
        return sum;
    }

    public void reset() {
        super.fillRelease(0);
    }
}
