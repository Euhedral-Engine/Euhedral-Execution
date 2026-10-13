package io.euhedral_execution.hardware_utils.topology.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MaskCodecTest {

    @Test
    void chunkCountLimitIsEnforced() {
        // A real string just over the limit would need hundreds of MB of heap, so test the guard directly.
        assertDoesNotThrow(() -> MaskCodec.checkChunkCount(1 << 26));
        NumberFormatException failure =
                assertThrows(NumberFormatException.class, () -> MaskCodec.checkChunkCount((1 << 26) + 1));
        assertTrue(failure.getMessage().contains("chunks"), failure.getMessage());
        assertThrows(NumberFormatException.class, () -> MaskCodec.checkChunkCount(Integer.MAX_VALUE));
    }
}
