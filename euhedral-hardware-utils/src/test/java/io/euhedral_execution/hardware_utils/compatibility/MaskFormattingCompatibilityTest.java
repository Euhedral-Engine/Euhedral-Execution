package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.euhedral_execution.hardware_utils.topology.SystemInfo;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
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

    @Test
    void rejectsChunksWiderThan32Bits() {
        assertThrows(IllegalArgumentException.class, () -> SystemInfo.fromHexMask("ffffffffff"));
    }

    @Test
    void rejectsEmbeddedPrefix() {
        assertThrows(IllegalArgumentException.class, () -> SystemInfo.fromHexMask("ff0x01"));
    }

    @Test
    void acceptsUpperCasePrefix() {
        assertEquals(BitSet.valueOf(new long[] {0x3L}), SystemInfo.fromHexMask("0X3"));
    }

    @Test
    void highChunkMapsToBit32() {
        BitSet expected = new BitSet();
        expected.set(32);
        assertEquals(expected, SystemInfo.fromHexMask("1,00000000"));
    }

    @Test
    void roundTripsSparseHighBits() {
        BitSet set = new BitSet();
        set.set(0);
        set.set(63);
        set.set(64);
        set.set(1023);
        assertEquals(set, SystemInfo.fromHexMask(SystemInfo.toHexMask(set)));
    }

    @Test
    void keepsPreviouslyValidInputsParsingToSameBits() {
        Map<String, BitSet> cases = new LinkedHashMap<>();
        cases.put("1,", bits(0));
        cases.put(",", bits());
        cases.put("1,,,", bits(0));
        cases.put(",,", bits());
        cases.put("0000000001", bits(0));
        cases.put("0000000000000000000000000001", bits(0));
        cases.put("0000", bits());
        cases.put("+1", bits(0));
        cases.put("+0x1", bits(0));
        cases.put("0x+1", bits(0));
        cases.put("0x 1", bits(0));
        cases.put(" 1 ", bits(0));
        cases.put("\t0x\t3\t", bits(0, 1));
        cases.put("1,00000000", bits(32));
        cases.put("00000000,00000001", bits(0));
        cases.forEach((text, expected) -> assertEquals(expected, SystemInfo.fromHexMask(text), text));
    }

    @Test
    void stillRejectsSilentlyMisparsedAndMalformedInputs() {
        for (String text : List.of("1,,2", ",1", "00x1", "0x0x1", "+ 1", "1, ", "0x", "+", "100000000")) {
            assertThrows(NumberFormatException.class, () -> SystemInfo.fromHexMask(text), text);
        }
    }
}
