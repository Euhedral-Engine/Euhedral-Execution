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
}

