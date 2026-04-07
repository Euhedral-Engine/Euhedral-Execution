package euhedral.io.utils;

import net.openhft.hashing.LongHashFunction;

public final class KeyHasher {

    private static final long SEED = 0x9e3779b97f4a7c15L;

    public static long getHash(byte[] data) {
        return LongHashFunction.xx3().hashBytes(data);
    }

    public static long getHash(byte[] data1, byte[] data2) {
        long hash1 = LongHashFunction.xx3().hashBytes(data1);
        return LongHashFunction.xx3(hash1).hashBytes(data2);
    }

    public static long getHash(String string) {
        return LongHashFunction.xx3().hashChars(string);
    }

    public static long getHash(String string1, String string2) {
        long hash1 = LongHashFunction.xx3().hashChars(string1);
        return LongHashFunction.xx3(hash1).hashChars(string2);
    }

    public static long getHash(String string, long seed) {
        return LongHashFunction.xx3(seed).hashChars(string);
    }

    public static long mix(long hash) {
        return LongHashFunction.xx3().hashLong(hash);
    }

    // MurmurHash3
    public static long combine(long hash1, long hash2) {
        return LongHashFunction.xx3(hash1).hashLong(hash2);
    }
}
