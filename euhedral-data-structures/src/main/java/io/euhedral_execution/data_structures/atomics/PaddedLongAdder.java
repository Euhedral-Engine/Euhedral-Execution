package io.euhedral_execution.data_structures.atomics;

import java.lang.invoke.VarHandle;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("unused")
public final class PaddedLongAdder extends PaddedAtomicLongArray {

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
