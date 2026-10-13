package io.euhedral_execution.hardware_utils.topology.internal;

import java.util.BitSet;
import java.util.StringJoiner;

public final class MaskCodec {

    private MaskCodec() {}

    /**
     * Parses a kernel-style hex mask: comma-separated 32-bit chunks, most significant chunk first.
     * Each chunk is 1-8 hex digits with optional surrounding whitespace and a single leading
     * {@code 0x}/{@code 0X}. Chunk {@code k} from the right contributes bits {@code k*32 .. k*32+31}.
     * Parsing is allocation-free apart from the result, so it does not use regex or {@code split}.
     */
    public static BitSet parse(String mask) {
        int end = mask.length();
        int chunkCount = 1;
        for (int i = 0; i < end; i++) {
            if (mask.charAt(i) == ',') {
                chunkCount++;
            }
        }
        BitSet set = new BitSet(32 * chunkCount);
        int start = 0;
        for (int index = 0; index < chunkCount; index++) {
            int next = mask.indexOf(',', start);
            if (next < 0) {
                next = end;
            }
            parseChunk(mask, start, next, index, chunkCount - 1 - index, set);
            start = next + 1;
        }
        return set;
    }

    private static void parseChunk(String mask, int from, int to, int index, int fromRight, BitSet set) {
        int s = from;
        int e = to;
        while (s < e && mask.charAt(s) <= ' ') {
            s++;
        }
        while (e > s && mask.charAt(e - 1) <= ' ') {
            e--;
        }
        if (e - s >= 2 && mask.charAt(s) == '0' && (mask.charAt(s + 1) == 'x' || mask.charAt(s + 1) == 'X')) {
            s += 2;
        }
        int digits = e - s;
        if (digits < 1 || digits > 8) {
            throw new NumberFormatException(
                    "invalid hex mask chunk at index " + index + ": \"" + mask.substring(from, to) + "\"");
        }
        long value = 0;
        for (int i = s; i < e; i++) {
            int digit = hexValue(mask.charAt(i));
            if (digit < 0) {
                throw new NumberFormatException(
                        "invalid hex mask chunk at index " + index + ": \"" + mask.substring(from, to) + "\"");
            }
            value = (value << 4) | digit;
        }
        int base = fromRight * 32;
        while (value != 0) {
            set.set(base + Long.numberOfTrailingZeros(value));
            value &= value - 1;
        }
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    public static String format(BitSet set) {
        if (set.isEmpty()) {
            return "0";
        }
        StringJoiner joiner = new StringJoiner(",");
        long[] bits = set.toLongArray();
        boolean headWritten = false;
        for (int i = bits.length - 1; i >= 0; i--) {
            long chunk = bits[i];
            int upper = (int) (chunk >>> 32);
            int lower = (int) chunk;
            if (headWritten) {
                joiner.add(String.format("%08x", upper));
            } else if (upper != 0) {
                joiner.add(Integer.toHexString(upper));
                headWritten = true;
            }
            if (headWritten) {
                joiner.add(String.format("%08x", lower));
            } else if (lower != 0 || i == 0) {
                joiner.add(Integer.toHexString(lower));
                headWritten = true;
            }
        }
        return joiner.toString();
    }
}
