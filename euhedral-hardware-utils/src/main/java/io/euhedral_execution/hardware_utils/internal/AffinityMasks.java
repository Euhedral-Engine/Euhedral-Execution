package io.euhedral_execution.hardware_utils.internal;

import java.util.Arrays;
import java.util.BitSet;

public final class AffinityMasks {

    public static final int MAX_BITS = 1_048_576;

    /// Copies and validates a little-endian logical CPU mask.
    ///
    /// Word `i`, bit `b` represents logical CPU `64 * i + b`. `span` is the exclusive upper bound
    /// for logical IDs. Unset bits in `supported` are sparse holes and are rejected.
    ///
    /// @return the owned mask without trailing zero words, or `null` when invalid
    public static long[] canonical(long[] source, int span, BitSet supported) {
        if (source == null || source.length > wordCount(span)) {
            return null;
        }
        long[] owned = source.clone();
        int length = owned.length;
        while (length > 0 && owned[length - 1] == 0) {
            length--;
        }
        if (length == 0) {
            return null;
        }
        long highest = ((long) (length - 1) << 6)
                + (63 - Long.numberOfLeadingZeros(owned[length - 1]));
        if (highest >= span) {
            return null;
        }
        for (int wordIndex = 0; wordIndex < length; wordIndex++) {
            long word = owned[wordIndex];
            while (word != 0) {
                int bit = Long.numberOfTrailingZeros(word);
                if (!supported.get((wordIndex << 6) + bit)) {
                    return null;
                }
                word &= word - 1;
            }
        }
        return length == owned.length ? owned : Arrays.copyOf(owned, length);
    }

    /// Counts words containing at least one requested CPU.
    ///
    /// Windows uses this to reject masks spanning multiple processor groups before JNI.
    public static int nonzeroWords(long[] mask) {
        int count = 0;
        for (long word : mask) {
            if (word != 0) {
                count++;
            }
        }
        return count;
    }

    private static int wordCount(int span) {
        return (span + 63) >>> 6;
    }

    private AffinityMasks() {
    }
}
