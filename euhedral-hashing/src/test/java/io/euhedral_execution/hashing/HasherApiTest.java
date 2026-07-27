package io.euhedral_execution.hashing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HasherApiTest {

    @Test
    void testDeterminism() {
        String input = "shard-routing-key-123";
        long hash1 = HasherApi.getHash(input);
        long hash2 = HasherApi.getHash(input);

        assertEquals(hash1, hash2, "Same input must produce same hash");
    }

    @Test
    void testAvalancheEffect() {
        long h1 = HasherApi.getHash("user_1000");
        long h2 = HasherApi.getHash("user_1001");

        assertNotEquals(h1, h2);
    }

    @Test
    void testStringAndByteEquality() {
        String input = "test-vector";
        byte[] bytes = input.getBytes(StandardCharsets.UTF_16LE);

        assertNotEquals(0, HasherApi.getHash(input));
        assertNotEquals(0, HasherApi.getHash(bytes));
        assertEquals(HasherApi.getHash(input), HasherApi.getHash(bytes));
    }

    @Test
    void testSeeding() {
        String input = "same-data";
        long seed1 = 0x12345L;
        long seed2 = 0x67890L;

        long h1 = HasherApi.getHash(input, seed1);
        long h2 = HasherApi.getHash(input, seed2);

        assertNotEquals(h1, h2, "Different seeds must produce different hashes");
    }

    @Test
    void testCombineAndMultiPart() {
        String part1 = "namespace";
        String part2 = "entityId";

        long manualCombine = HasherApi.getHash(part2, HasherApi.getHash(part1));
        long helperCombine = HasherApi.getHash(part1, part2);

        assertEquals(manualCombine, helperCombine, "Multi-part hashing should be chainable");
    }

    @Test
    void byteAndBufferHashersAgreeAtEveryTailBoundary() {
        int[] lengths = {0, 1, 3, 4, 7, 8, 15, 16, 31, 32, 33, 63, 64, 65};

        for (int length : lengths) {
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                bytes[i] = (byte) (i * 31 + 7);
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            long expected = BufferHasher.getHash(buffer, 0, length, AbstractHasher.BASE_SEED);

            assertEquals(expected, HasherApi.getHash(bytes), "length " + length);
        }
    }

    @Test
    void testZeroSafety() {
        assertNotEquals(0, HasherApi.mixWithZeroCheck(0));
        assertNotEquals(0, HasherApi.mixWithZeroCheck(Long.MAX_VALUE));
    }
}
