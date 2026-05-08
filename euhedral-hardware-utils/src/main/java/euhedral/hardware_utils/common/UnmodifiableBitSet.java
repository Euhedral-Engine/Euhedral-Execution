package euhedral.hardware_utils.common;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.BitSet;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;

public final class UnmodifiableBitSet extends BitSet {

    public static UnmodifiableBitSet wrap(BitSet set) throws Exception {
        return new UnmodifiableBitSet(set);
    }

    private final BitSet delegate;

    public UnmodifiableBitSet(BitSet delegate) throws Exception {
        this.delegate = delegate;

        Class<?> clazz = BitSet.class;
        Field words = clazz.getDeclaredField("words");
        Field wordsInUse = clazz.getDeclaredField("wordsInUse");
        Field sizeIsSticky = clazz.getDeclaredField("sizeIsSticky");

        words.setAccessible(true);
        wordsInUse.setAccessible(true);
        sizeIsSticky.setAccessible(true);

        long[] w = (long[]) words.get(delegate);
        words.set(this, Arrays.copyOf(w, w.length));
        wordsInUse.set(this, w.length);
        sizeIsSticky.set(this, sizeIsSticky.get(delegate));
    }

    @Override
    public byte[] toByteArray() {
        return delegate.toByteArray();
    }

    @Override
    public long[] toLongArray() {
        return delegate.toLongArray();
    }

    @Override
    public void flip(int i) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public void flip(int start, int end) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public void set(int i) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public void set(int i, boolean val) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public void set(int start, int end) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public void set(int start, int end, boolean val) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public void clear() {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public void clear(int i) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public void clear(int start, int end) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public boolean get(int i) {
        return delegate.get(i);
    }

    @Override
    public @NonNull BitSet get(int start, int end) {
        return delegate.get(start, end);
    }

    @Override
    public int nextSetBit(int fromIndex) {
        return delegate.nextSetBit(fromIndex);
    }

    @Override
    public int nextClearBit(int fromIndex) {
        return delegate.nextClearBit(fromIndex);
    }

    @Override
    public int previousSetBit(int fromIndex) {
        return delegate.previousSetBit(fromIndex);
    }

    @Override
    public int previousClearBit(int fromIndex) {
        return delegate.previousClearBit(fromIndex);
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public boolean intersects(@NonNull BitSet set) {
        return delegate.intersects(set);
    }

    @Override
    public int cardinality() {
        return delegate.cardinality();
    }

    @Override
    public void and(@NonNull BitSet set) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public void or(@NonNull BitSet set) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public void xor(@NonNull BitSet set) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public void andNot(@NonNull BitSet set) {
        throw new RuntimeException("This is an unmodifiable BitSet");
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean equals(Object obj) {
        return delegate.equals(obj);
    }

    @Override
    public Object clone() {
        return delegate.clone();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Override
    public IntStream stream() {
        return delegate.stream();
    }
}
