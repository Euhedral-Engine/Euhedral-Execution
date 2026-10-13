package io.euhedral_execution.hardware_utils.topology;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void recordToStringDoesNotTouchGlobals() {
        SystemInfo.CpuCacheLayout layout = new SystemInfo.CpuCacheLayout(
                Integer.MAX_VALUE - 1,
                SystemInfo.DEFAULT_L1,
                SystemInfo.DEFAULT_L2,
                SystemInfo.DEFAULT_L3,
                1,
                1,
                1,
                "1",
                "1",
                "1",
                64);
        assertDoesNotThrow(layout::toString);

        SystemInfo.CoreInfo pCore = new SystemInfo.CoreInfo("1", true, 0, 0);
        assertFalse(pCore.toString().contains("\u001b"), pCore.toString());
    }

    @Test
    void parsesKernelStyleCommaSeparatedMasks() {
        assertEquals(bits(0, 32, 63), SystemInfo.fromHexMask("80000001,00000001"));
        assertEquals(bits(0), SystemInfo.fromHexMask("0x1"));
        assertThrows(NumberFormatException.class, () -> SystemInfo.fromHexMask("not-a-mask"));
    }

    @Test
    void presenceAndStrictAccessorsAgreeWithNullableLookups() {
        int limit = Math.max(SystemInfo.getCpuCount(), SystemInfo.getMaxCoreId()) + 3;
        for (int id = -1; id < limit; id++) {
            final int probe = id;
            assertEquals(SystemInfo.getCpuInfo(id) != null, SystemInfo.isActiveCpu(id));
            assertEquals(SystemInfo.getCoreInfo(id) != null, SystemInfo.isActiveCore(id));
            assertEquals(SystemInfo.getSocketInfo(id) != null, SystemInfo.isActiveSocket(id));
            if (SystemInfo.isActiveCpu(id)) {
                assertEquals(SystemInfo.getCpuInfo(id), SystemInfo.getCpuInfoOrThrow(id));
            } else {
                assertThrows(IllegalArgumentException.class, () -> SystemInfo.getCpuInfoOrThrow(probe));
            }
        }
        assertFalse(SystemInfo.isActiveCpu(Integer.MAX_VALUE));
    }
}
