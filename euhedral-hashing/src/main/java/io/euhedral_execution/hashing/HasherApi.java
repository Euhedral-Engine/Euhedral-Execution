package io.euhedral_execution.hashing;

public final class HasherApi extends AbstractHasher {

    public static long getHash(byte[] data) {
        return ByteHasher.getHash(data);
    }

    public static long getHash(byte[] data, long seed) {
        return ByteHasher.getHash(data, seed);
    }

    public static long getHash(byte[] data1, byte[] data2) {
        return ByteHasher.getHash(data1, data2);
    }

    public static long getHash(String s) {
        return StringHasher.getHash(s);
    }

    public static long getHash(String s1, String s2) {
        return StringHasher.getHash(s1, s2);
    }

    public static long getHash(String s, long seed) {
        return StringHasher.getHash(s, seed);
    }

    /**
     * Hashes the raw IEEE-754 representation of a double array without allocating an intermediate
     * byte buffer. This is the xxHash64 lane layout used by the other hashers in this module.
     */
    public static long getHash(double[] data) {
        return getHash(data, BASE_SEED);
    }

    /**
     * Hashes the raw IEEE-754 representation of a double array with the supplied seed.
     */
    public static long getHash(double[] data, long seed) {
        int index = 0;
        long hash;

        if (data.length >= 4) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;

            int limit = data.length - 4;
            while (index <= limit) {
                v1 = round(v1, Double.doubleToRawLongBits(data[index]));
                v2 = round(v2, Double.doubleToRawLongBits(data[index + 1]));
                v3 = round(v3, Double.doubleToRawLongBits(data[index + 2]));
                v4 = round(v4, Double.doubleToRawLongBits(data[index + 3]));
                index += 4;
            }
            hash = merge(v1, v2, v3, v4);
        } else {
            hash = seed + P5;
        }

        hash += (long) data.length * Double.BYTES;
        while (index < data.length) {
            hash = tail1(hash, Double.doubleToRawLongBits(data[index++]));
        }
        return mix(hash);
    }

    public static long mixWithZeroCheck(long hash) {
        long h64 = mix(hash);
        return h64 == 0 ? BASE_SEED : h64;
    }

    public static long mix(long hash) {
        hash ^= hash >>> 33;
        hash *= P2;
        hash ^= hash >>> 29;
        hash *= P3;
        hash ^= hash >>> 32;
        return hash;
    }

    private HasherApi() {

    }
}
