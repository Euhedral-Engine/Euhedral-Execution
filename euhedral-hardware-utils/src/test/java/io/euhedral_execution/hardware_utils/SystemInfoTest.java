package io.euhedral_execution.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemInfoTest {

    private static BitSet bits(int... indexes) {
        BitSet set = new BitSet();
        for (int index : indexes) {
            set.set(index);
        }
        return set;
    }

    @Test
    void hexadecimalCpuMasksRoundTripAcrossChunkBoundaries() {
        for (BitSet expected : List.of(bits(), bits(0), bits(31, 32), bits(0, 63, 64, 95, 127))) {
            String mask = SystemInfo.toHexMask(expected);
            assertEquals(expected, SystemInfo.fromHexMask(mask), mask);
        }
    }

    @Test
    void parsesKernelStyleCommaSeparatedMasks() {
        assertEquals(bits(0, 32, 63), SystemInfo.fromHexMask("80000001,00000001"));
        assertEquals(bits(0), SystemInfo.fromHexMask("0x1"));
        assertThrows(NumberFormatException.class, () -> SystemInfo.fromHexMask("not-a-mask"));
    }
}
