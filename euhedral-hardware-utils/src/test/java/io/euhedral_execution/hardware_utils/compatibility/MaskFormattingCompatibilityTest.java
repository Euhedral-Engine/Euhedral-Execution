package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.euhedral_execution.hardware_utils.SystemInfo;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaskFormattingCompatibilityTest {

    private static BitSet bits(int... indexes) {
        BitSet result = new BitSet();
        for (int index : indexes) {
            result.set(index);
        }
        return result;
    }

    @Test
    void preservesCanonicalCpuMaskText() {
        Map<BitSet, String> cases = new LinkedHashMap<>();
        cases.put(bits(), "0");
        cases.put(bits(0), "1");
        cases.put(bits(31, 32), "1,80000000");
        cases.put(bits(0, 32, 63), "80000001,00000001");
        cases.put(bits(0, 64), "1,00000000,00000001");
        cases.put(bits(127), "80000000,00000000,00000000,00000000");

        cases.forEach((bits, text) -> {
            assertEquals(text, SystemInfo.toHexMask(bits));
            assertEquals(bits, SystemInfo.fromHexMask(text));
        });
        assertEquals(bits(0), SystemInfo.fromHexMask("0x1"));
        assertEquals(bits(0, 32, 63), SystemInfo.fromHexMask("80000001,00000001"));
        assertThrows(NumberFormatException.class, () -> SystemInfo.fromHexMask("not-hex"));
        assertThrows(NumberFormatException.class, () -> SystemInfo.fromHexMask("0x"));
    }
}
