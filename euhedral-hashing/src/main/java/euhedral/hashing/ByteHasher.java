package euhedral.hashing;

public final class ByteHasher extends AbstractHasher {

    public static long getHash(byte[] data) {
        return getHash(data, BASE_SEED);
    }

    public static long getHash(byte[] data, long seed) {
        int len = data.length;
        long h64;

        if (len >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;

            int limit = len - 32;
            int i = 0;
            for (; i <= limit; i += 32) {
                v1 = round(v1, readLong(data, i));
                v2 = round(v2, readLong(data, i + 8));
                v3 = round(v3, readLong(data, i + 16));
                v4 = round(v4, readLong(data, i + 24));
            }

            h64 = merge(v1, v2, v3, v4);
        } else {
            h64 = seed + P5;
        }

        h64 += len;

        int i = (len >= 32) ? (len / 32) * 32 : 0;
        while (i <= len - 8) {
            h64 = tail_1(h64, readLong(data, i));
            i += 8;
        }
        if (i <= len - 4) {
            h64 = tail_2(h64, readInt(data, i));
            i += 4;
        }
        while (i < len) {
            h64 = tail_3(h64, data[i++]);
        }

        return mix(h64);
    }

    private static long readLong(byte[] data, int i) {
        return (data[i] & 0xFFL) |
                ((data[i + 1] & 0xFFL) << 8) |
                ((data[i + 2] & 0xFFL) << 16) |
                ((data[i + 3] & 0xFFL) << 24) |
                ((data[i + 4] & 0xFFL) << 32) |
                ((data[i + 5] & 0xFFL) << 40) |
                ((data[i + 6] & 0xFFL) << 48) |
                ((data[i + 7] & 0xFFL) << 56);
    }

    private static int readInt(byte[] data, int i) {
        return (data[i] & 0xFF) |
                ((data[i + 1] & 0xFF) << 8) |
                ((data[i + 2] & 0xFF) << 16) |
                ((data[i + 3] & 0xFF) << 24);
    }

    public static long getHash(byte[] data1, byte[] data2) {
        return getHash(data2, getHash(data1, BASE_SEED));
    }
}
