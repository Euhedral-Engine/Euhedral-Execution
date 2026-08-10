package io.euhedral_execution.hardware_utils.common;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.DoubleConsumer;

public final class UnmodifiableDoubleArray {
    private final double[] delegate;

    public UnmodifiableDoubleArray(double[] delegate) {
        this.delegate = Objects.requireNonNull(delegate).clone();
    }

    public static UnmodifiableDoubleArray wrap(double[] delegate) {
        return new UnmodifiableDoubleArray(delegate);
    }

    public double get(int idx) {
        return this.delegate[idx];
    }

    public void copy(double[] buffer, int bufferStart, int bufferEnd, int sourceStart) {
        Objects.requireNonNull(buffer);
        if (bufferStart < 0 || bufferStart > bufferEnd || bufferEnd > buffer.length || sourceStart < 0) {
            throw new IndexOutOfBoundsException();
        }
        while (bufferStart < bufferEnd && sourceStart < delegate.length) {
            buffer[bufferStart++] = delegate[sourceStart++];
        }
    }

    public void iterate(int start, int end, DoubleConsumer consumer) {
        Objects.requireNonNull(consumer);
        if (start < 0 || start > end || end > delegate.length) {
            throw new IndexOutOfBoundsException();
        }
        while (start < end) {
            consumer.accept(delegate[start++]);
        }
    }

    public int length() {
        return this.delegate.length;
    }

    @Override
    public String toString() {
        return Arrays.toString(delegate);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UnmodifiableDoubleArray values && Arrays.equals(delegate, values.delegate);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(delegate);
    }
}
