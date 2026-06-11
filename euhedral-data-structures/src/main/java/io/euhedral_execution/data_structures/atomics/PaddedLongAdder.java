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
        super.incrementAndGet(super.fromRawIdx(rand));
    }

    public void increment(int idx) {
        super.incrementAndGet(idx);
    }

    public void decrement() {
        long rand = ThreadLocalRandom.current().nextLong();
        super.decrementAndGet(super.fromRawIdx(rand));
    }

    public void decrement(int idx) {
        super.decrementAndGet(idx);
    }

    public void add(long value) {
        long rand = ThreadLocalRandom.current().nextLong();
        super.addAndGet(super.fromRawIdx(rand), value);
    }

    public void add(int idx, long value) {
        super.addAndGet(idx, value);
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
                sum += super.array[i];
                super.array[i] = 0;
            }
            VarHandle.fullFence();
        }
        return sum;
    }

    public void reset() {
        super.fillRelease(0);
    }
}
