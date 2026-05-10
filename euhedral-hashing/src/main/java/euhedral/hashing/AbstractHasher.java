package euhedral.hashing;

public class AbstractHasher {

    public static final long BASE_SEED = 0x9e3779b97f4a7c15L;

    // xxHash64 Primes
    protected static final long P1 = 0x9E3779B97F4A7C15L;   // 11400714819323198485
    protected static final long P2 = 0x9E3779B185EBCA8FL;   // 11400714785074694799
    protected static final long P3 = 0xC291F4F83810098FL;   // 14020256461910706575
    protected static final long P4 = 0x53EF6F2651046A29L;   // 6048175034940549673
    protected static final long P5 = 0x27D4EB2F165667C5L;   // 2870177450012600261

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

    protected static long tail_1(long h64, long val) {
        long k1 = round(0, val);
        return Long.rotateLeft(h64 ^ k1, 27) * P1 + P4;
    }

    protected static long tail_2(long h64, long val) {
        h64 ^= (val & 0xFFFFFFFFL) * P1;
        return Long.rotateLeft(h64, 23) * P2 + P3;
    }

    protected static long tail_3(long h64, long val) {
        return Long.rotateLeft(h64 ^ (val & 0xFFL) * P5, 11) * P1;
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

    public static long combine(long h1, long h2) {
        return mix(h1 ^ (h2 * P1));
    }
}
