package euhedral.atomics;

import java.lang.invoke.VarHandle;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("unused")
public final class PaddedLongAdder extends PaddedAtomicLongArray {

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
        for(int i = 0; i < super.length(); i++) {
            int pIdx = super.boundsCheck ? i : ((i + 1) * this.padding) + i;
            sum += super.array[pIdx];
        }

        VarHandle.acquireFence();
        return sum;
    }

    public long sumAndReset() {
        long sum = 0;
        for(int i = 0; i < super.length(); i++) {
            int pIdx = super.boundsCheck ? i : ((i + 1) * this.padding) + i;
            sum += super.getAndSet(pIdx, 0);
        }
        return sum;
    }
}
