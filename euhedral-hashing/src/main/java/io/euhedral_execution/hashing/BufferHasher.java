package io.euhedral_execution.hashing;

import java.nio.ByteBuffer;

public class BufferHasher extends AbstractHasher {

    public static long getHash(ByteBuffer buffer, int start, int end, long seed) {
        final int diff = end - start;

        long h64;
        int i = start;
        if(diff >= 32) {
            long v1 = seed + AbstractHasher.P1 + AbstractHasher.P2;
            long v2 = seed + AbstractHasher.P2;
            long v3 = seed;
            long v4 = seed - AbstractHasher.P1;

            int limit = end - 32;
            for(; i <= limit; i += 32) {
                v1 = round(v1, buffer.getLong(i));
                v2 = round(v2, buffer.getLong(i + 8));
                v3 = round(v3, buffer.getLong(i + 16));
                v4 = round(v4, buffer.getLong(i + 24));
            }

            h64 = merge(v1, v2, v3, v4);
        } else {
            h64 = seed + P5;
        }

        h64 += diff;

        while (i <= end - 8) {
            h64 = tail1(h64, buffer.getLong(i));
            i += 8;
        }
        if (i <= end - 4) {
            h64 = tail2(h64, buffer.getInt(i));
            i += 4;
        }
        while (i < end) {
            h64 = tail3(h64, buffer.get(i++));
        }

        return mix(h64);
    }

    public BufferHasher() {}

}
