package io.euhedral_execution.hashing;

public class AbstractHasher {

    public static final long BASE_SEED = 0x9e3779b97f4a7c15L;

    // xxHash64 Primes
    protected static final long P1 = 0x9E3779B185EBCA87L;
    protected static final long P2 = 0xC2B2AE3D27D4EB4FL;
    protected static final long P3 = 0x165667B19E3779F9L;
    protected static final long P4 = 0x85EBCA77C2B2AE63L;
    protected static final long P5 = 0x27D4EB2F165667C5L;

    protected AbstractHasher() {

    }

    protected static long merge(long v1, long v2, long v3, long v4) {
        long h64;
        h64 = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7) +
                Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
        h64 = mergeRound(h64, v1);
        h64 = mergeRound(h64, v2);
        h64 = mergeRound(h64, v3);
        h64 = mergeRound(h64, v4);
        return h64;
    }

    private static long mergeRound(long acc, long val) {
        val = round(0, val);
        acc ^= val;
        return acc * P1 + P4;
    }

    protected static long round(long acc, long input) {
        acc += input * P2;
        return Long.rotateLeft(acc, 31) * P1;
    }

    protected static long tail1(long h64, long val) {
        long k1 = round(0, val);
        return Long.rotateLeft(h64 ^ k1, 27) * P1 + P4;
    }

    protected static long tail2(long h64, long val) {
        h64 ^= (val & 0xFFFFFFFFL) * P1;
        return Long.rotateLeft(h64, 23) * P2 + P3;
    }

    protected static long tail3(long h64, long val) {
        return Long.rotateLeft(h64 ^ (val & 0xFFL) * P5, 11) * P1;
    }

    protected static long mix(long hash) {
        hash ^= hash >>> 33;
        hash *= P2;
        hash ^= hash >>> 29;
        hash *= P3;
        hash ^= hash >>> 32;
        return hash;
    }

    public static long combine(long h1, long h2) {
        return mix(h1 ^ (h2 * P1));
    }
}
