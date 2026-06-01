package euhedral.hashing;

public final class StringHasher extends AbstractHasher {

    public static long getHash(String s1, String s2) {
        return StringHasher.getHash(s2, getHash(s1));
    }

    public static long getHash(String s, long seed) {
        final int charLen = s.length();
        final int byteLen = charLen * 2;
        long h64;

        int i = 0;
        if (byteLen >= 32) {
            long v1 = seed + AbstractHasher.P1 + AbstractHasher.P2;
            long v2 = seed + AbstractHasher.P2;
            long v3 = seed;
            long v4 = seed - AbstractHasher.P1;

            int limit = charLen - 16;
            for (; i <= limit; i += 16) {
                v1 = AbstractHasher.round(v1, readLongFromChars(s, i));
                v2 = AbstractHasher.round(v2, readLongFromChars(s, i + 4));
                v3 = AbstractHasher.round(v3, readLongFromChars(s, i + 8));
                v4 = AbstractHasher.round(v4, readLongFromChars(s, i + 12));
            }

            h64 = AbstractHasher.merge(v1, v2, v3, v4);
        } else {
            h64 = seed + AbstractHasher.P5;
        }

        h64 += byteLen;

        while (i <= charLen - 4) {
            h64 = AbstractHasher.tail_1(h64, readLongFromChars(s, i));
            i += 4;
        }
        if (i <= charLen - 2) {
            h64 = AbstractHasher.tail_2(h64, readIntFromChars(s, i));
            i += 2;
        }
        if (i < charLen) {
            int c = s.charAt(i);
            h64 = AbstractHasher.tail_3(h64, c);
            h64 = AbstractHasher.tail_3(h64, c >> 8);
        }
        return AbstractHasher.mix(h64);
    }

    public static long getHash(String s) {
        return StringHasher.getHash(s, AbstractHasher.BASE_SEED);
    }

    private static long readLongFromChars(String s, int i) {
        return (long) s.charAt(i) |
                ((long) s.charAt(i + 1) << 16) |
                ((long) s.charAt(i + 2) << 32) |
                ((long) s.charAt(i + 3) << 48);
    }

    private static int readIntFromChars(String s, int i) {
        return s.charAt(i) | (s.charAt(i + 1) << 16);
    }
}
