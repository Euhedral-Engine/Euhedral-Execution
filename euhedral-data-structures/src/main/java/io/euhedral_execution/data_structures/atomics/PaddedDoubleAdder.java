package io.euhedral_execution.data_structures.atomics;

import java.lang.invoke.VarHandle;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("unused")
public final class PaddedDoubleAdder extends PaddedAtomicDoubleArray {
    public PaddedDoubleAdder() {
        super(Runtime.getRuntime().availableProcessors(), true, false);
    }

    public PaddedDoubleAdder(int length) {
        super(length, true, false);
    }

    public PaddedDoubleAdder(int length, boolean boundsCheck, boolean pad128) {
        super(length, boundsCheck, pad128);
    }

    public void increment() {
        long rand = ThreadLocalRandom.current().nextLong();
        super.getAndAddRelease(super.fromRawIdx(rand), 1.0);
    }

    public void increment(int idx) {
        super.getAndAddRelease(idx, 1.0);
    }

    public void decrement() {
        long rand = ThreadLocalRandom.current().nextLong();
        super.getAndAddRelease(super.fromRawIdx(rand), -1.0);
    }

    public void decrement(int idx) {
        super.getAndAddRelease(idx, -1.0);
    }

    public void add(double value) {
        long rand = ThreadLocalRandom.current().nextLong();
        super.getAndAddRelease(super.fromRawIdx(rand), value);
    }

    public void add(int idx, double value) {
        super.getAndAddRelease(idx, value);
    }

    public double min() {
        double min = Double.MAX_VALUE;
        if (super.boundsCheck) {
            for (int i = 0; i < super.length(); i++) {
                double temp = super.array[((i + 1) * super.padding) + i];
                if(!Double.isFinite(temp)) {
                    continue;
                }
                min = Math.min(min, temp);
            }
            VarHandle.acquireFence();
        } else {
            for (double d : super.array) {
                if(!Double.isFinite(d)) {
                    continue;
                }
                min = Math.min(min, d);
            }
            VarHandle.acquireFence();
        }
        return min;
    }

    public double max() {
        double max = Double.MIN_VALUE;
        if (super.boundsCheck) {
            for (int i = 0; i < super.length(); i++) {
                double temp = super.array[((i + 1) * super.padding) + i];
                if(!Double.isFinite(temp)) {
                    continue;
                }
                max = Math.max(max, temp);
            }
            VarHandle.acquireFence();
        } else {
            for (double d : super.array) {
                if(!Double.isFinite(d)) {
                    continue;
                }
                max = Math.max(max, d);
            }
            VarHandle.acquireFence();
        }
        return max;
    }

    public double mean() {
        double sum = 0;
        int count = 0;
        if (super.boundsCheck) {
            for (int i = 0; i < super.length(); i++) {
                double temp = super.array[((i + 1) * super.padding) + i];
                if(Double.isFinite(temp)) {
                    sum += temp;
                    count++;
                }
            }
            VarHandle.acquireFence();
        } else {
            for (double d : super.array) {
                if(!Double.isFinite(d)) {
                    continue;
                }
                sum += d;
                count++;
            }
            VarHandle.acquireFence();
        }
        return count == 0 ? 0 : sum / count;
    }

    public double sum() {
        double sum = 0;
        if (super.boundsCheck) {
            for (int i = 0; i < super.length(); i++) {
                double temp = super.array[((i + 1) * super.padding) + i];
                if(Double.isFinite(temp)) {
                    sum += temp;
                }
            }
            VarHandle.acquireFence();
        } else {
            for (double d : super.array) {
                if(!Double.isFinite(d)) {
                    continue;
                }
                sum += d;
            }
            VarHandle.acquireFence();
        }
        return sum;
    }

    public double sumAndReset() {
        double sum = 0;
        if (boundsCheck) {
            for (int i = 0; i < super.length(); i++) {
                double temp = super.getAndSet(i, 0);
                if(Double.isFinite(temp)) {
                    sum += temp;
                }
            }
        } else {
            for (int i = 0; i < super.array.length; i++) {
                double temp = super.getAndSet(i, 0);
                if(Double.isFinite(temp)) {
                    sum += temp;
                }
            }
        }
        return sum;
    }

    public void reset() {
        super.fillRelease(0);
    }
}
