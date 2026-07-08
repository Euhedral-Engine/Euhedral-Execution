package io.euhedral_execution.hardware_utils.common;

import java.util.Arrays;
import java.util.function.Consumer;

public final class UnmodifiableDoubleArray {
    private final double[] delegate;

    public static UnmodifiableDoubleArray wrap(double[] delegate) {
        return new UnmodifiableDoubleArray(delegate);
    }

    public UnmodifiableDoubleArray(double[] delegate) {
        this.delegate = delegate;
    }

    public double get(int idx) {
        return this.delegate[idx];
    }

    public void copy(double[] buffer, int buffStart, int buffEnd, int targetIdx) {
        while(buffStart < buffEnd && targetIdx < this.delegate.length) {
            buffer[buffStart++] = this.delegate[targetIdx++];
        }
    }

    public void iterate(int start, int end, Consumer<Double> consumer) {
        while(start < end) {
            consumer.accept(this.delegate[start++]);
        }
    }

    public int length() {
        return this.delegate.length;
    }

    @Override
    public String toString() {
        return Arrays.toString(delegate);
    }
}
