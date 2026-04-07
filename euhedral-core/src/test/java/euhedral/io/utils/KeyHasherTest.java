package euhedral.io.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class KeyHasherTest {

    @Test
    void testDeterminism() {
        String input = "shard-routing-key-123";
        long hash1 = KeyHasher.getHash(input);
        long hash2 = KeyHasher.getHash(input);

        assertEquals(hash1, hash2, "Same input must produce same hash");
    }

    @Test
    void testAvalancheEffect() {
        long h1 = KeyHasher.getHash("user_1000");
        long h2 = KeyHasher.getHash("user_1001");

        assertNotEquals(h1, h2);
        assertTrue(Long.bitCount(h1 ^ h2) > 20, "Bit flip count should be significant");
    }

    @Test
    void testStringAndByteEquality() {
        String input = "test-vector";
        byte[] bytes = input.getBytes(StandardCharsets.UTF_16LE);

        assertNotEquals(0, KeyHasher.getHash(input));
        assertNotEquals(0, KeyHasher.getHash(bytes));
        assertEquals(KeyHasher.getHash(input), KeyHasher.getHash(bytes));
    }

    @Test
    void testSeeding() {
        String input = "same-data";
        long seed1 = 0x12345L;
        long seed2 = 0x67890L;

        long h1 = KeyHasher.getHash(input, seed1);
        long h2 = KeyHasher.getHash(input, seed2);

        assertNotEquals(h1, h2, "Different seeds must produce different hashes");
    }

    @Test
    void testCombineAndMultiPart() {
        String part1 = "namespace";
        String part2 = "entityId";

        long manualCombine = KeyHasher.getHash(part2, KeyHasher.getHash(part1));
        long helperCombine = KeyHasher.getHash(part1, part2);

        assertEquals(manualCombine, helperCombine, "Multi-part hashing should be chainable");
    }

    @Test
    void testDistribution() {
        int cores = 16;
        int[] buckets = new int[cores];
        int iterations = 100_000;

        for (int i = 0; i < iterations; i++) {
            long hash = KeyHasher.getHash("key-" + i);
            int bucket = (int) Math.unsignedMultiplyHigh(hash, cores);
            buckets[bucket]++;
        }
        System.out.println(Arrays.toString(buckets));


        // Each bucket should have ~6250 hits. Ensure no bucket is empty.
        for (int count : buckets) {
            assertTrue(count > iterations / (cores * 2), "Distribution too skewed");
        }
    }

    @Test
    void testZeroSafety() {
        assertNotEquals(0, KeyHasher.mix(0));
        assertNotEquals(0, KeyHasher.mix(Long.MAX_VALUE));
    }
}