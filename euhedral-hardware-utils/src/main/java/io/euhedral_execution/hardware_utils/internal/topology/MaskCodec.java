package io.euhedral_execution.hardware_utils.internal.topology;

import java.util.BitSet;
import java.util.StringJoiner;

public final class MaskCodec {

    public static BitSet parse(String mask) {
        String[] chunks = mask.split(",");
        int bit = 0;
        BitSet set = new BitSet(32 * chunks.length);
        for (int i = chunks.length - 1; i >= 0; i--) {
            long subMask = Long.parseUnsignedLong(chunks[i].replace("0x", "").trim(), 16);
            int shifts = 0;
            while (subMask > 0) {
                int cpu = Long.numberOfTrailingZeros(subMask) + 1;
                shifts += cpu;
                bit += cpu;
                set.set(bit - 1);
                subMask >>>= cpu;
            }
            bit += 32 - shifts;
        }
        return set;
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

    private MaskCodec() {
    }
}
