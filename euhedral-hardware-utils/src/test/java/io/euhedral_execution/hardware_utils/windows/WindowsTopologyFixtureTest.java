package io.euhedral_execution.hardware_utils.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.windows.win32.GroupAffinity;
import io.euhedral_execution.hardware_utils.windows.win32.ProcessorRelationship;
import io.euhedral_execution.hardware_utils.windows.win32.Relationship;
import io.euhedral_execution.hardware_utils.windows.win32.SystemLogicalProcessorInformation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowsTopologyFixtureTest {

    private static ProcessorRelationship relationship(Relationship relationship, GroupAffinity... affinities) {
        return new ProcessorRelationship(relationship, false, true, List.of(affinities));
    }

    private static GroupAffinity affinity(long mask, int group) {
        return new GroupAffinity(mask, (short) group);
    }

    private static byte[] createProcessorRecord(int relationship, byte eClass, GroupAffinity... groups) {
        int groupCount = groups.length;
        int payloadLen = 24 + groupCount * 16;
        int size = 8 + payloadLen;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(relationship);
        buf.putInt(size);
        buf.put((byte) 0);
        buf.put(eClass);
        for (int i = 0; i < 20; i++) {
            buf.put((byte) 0);
        }
        buf.putShort((short) groupCount);
        for (GroupAffinity g : groups) {
            buf.putLong(g.mask());
            buf.putShort(g.group());
            for (int i = 0; i < 6; i++) {
                buf.put((byte) 0);
            }
        }
        return buf.array();
    }

    private static byte[] createCacheRecord(byte level, byte assoc, int cacheSize, int type, GroupAffinity... groups) {
        int groupCount = groups.length;
        int payloadLen = 32 + groupCount * 16;
        int size = 8 + payloadLen;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(2);
        buf.putInt(size);
        buf.put(level);
        buf.put(assoc);
        buf.putShort((short) 64);
        buf.putInt(cacheSize);
        buf.putInt(type);
        for (int i = 0; i < 18; i++) {
            buf.put((byte) 0);
        }
        buf.putShort((short) groupCount);
        for (GroupAffinity g : groups) {
            buf.putLong(g.mask());
            buf.putShort(g.group());
            for (int i = 0; i < 6; i++) {
                buf.put((byte) 0);
            }
        }
        return buf.array();
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            total += a.length;
        }
        byte[] res = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, res, pos, a.length);
            pos += a.length;
        }
        return res;
    }

    @Test
    void mapsGroupsAndBitSixtyThreeBijectively() {
        ProcessorRelationship packageValue = relationship(
                Relationship.PROCESSOR_PACKAGE,
                affinity(1, 0),
                affinity(Long.MIN_VALUE, 0),
                affinity(1, 1),
                affinity(Long.MIN_VALUE, 1));
        WindowsSystemLayout layout = new WindowsSystemLayout(List.of(
                relationship(Relationship.PROCESSOR_CORE, affinity(Long.MIN_VALUE, 1)),
                relationship(Relationship.PROCESSOR_CORE, affinity(1, 0)),
                packageValue,
                relationship(Relationship.PROCESSOR_CORE, affinity(1, 1)),
                relationship(Relationship.PROCESSOR_CORE, affinity(Long.MIN_VALUE, 0))));

        assertEquals(List.of(0, 63, 64, 127), List.copyOf(layout.getCpuInfoMap().keySet()));
        for (int cpu : List.of(0, 63, 64, 127)) {
            assertNotNull(layout.getCacheLayout().get(cpu));
        }
    }

    @Test
    void parsesSingleGroupBinaryBufferFixture() {
        // CPUs in Group 0: mask 0x0F (CPUs 0..3)
        byte[] packageRec = createProcessorRecord(3, (byte) 0, affinity(0x0FL, 0));
        byte[] core0 = createProcessorRecord(0, (byte) 1, affinity(1L, 0));
        byte[] core1 = createProcessorRecord(0, (byte) 1, affinity(2L, 0));
        byte[] core2 = createProcessorRecord(0, (byte) 1, affinity(4L, 0));
        byte[] core3 = createProcessorRecord(0, (byte) 1, affinity(8L, 0));
        byte[] l3Cache = createCacheRecord((byte) 3, (byte) 16, 16384 * 1024, 0, affinity(0x0FL, 0));

        byte[] rawBuffer = concat(packageRec, core0, core1, core2, core3, l3Cache);
        List<SystemLogicalProcessorInformation> parsed = SystemLogicalProcessorInformation.parse(rawBuffer);
        assertEquals(6, parsed.size());

        WindowsSystemLayout layout = new WindowsSystemLayout(parsed);
        assertEquals(4, layout.getCpuInfoMap().size());
        assertEquals(List.of(0, 1, 2, 3), List.copyOf(layout.getCpuInfoMap().keySet()));
    }

    @Test
    void parsesMultiGroupBinaryBufferFixture() {
        // Group 0 (mask 0x03 -> CPUs 0, 1) and Group 1 (mask 0x03 -> CPUs 64, 65)
        byte[] packageRec = createProcessorRecord(3, (byte) 0, affinity(3L, 0), affinity(3L, 1));
        byte[] core0 = createProcessorRecord(0, (byte) 0, affinity(1L, 0));
        byte[] core1 = createProcessorRecord(0, (byte) 0, affinity(2L, 0));
        byte[] core2 = createProcessorRecord(0, (byte) 0, affinity(1L, 1));
        byte[] core3 = createProcessorRecord(0, (byte) 0, affinity(2L, 1));

        byte[] rawBuffer = concat(packageRec, core0, core1, core2, core3);
        List<SystemLogicalProcessorInformation> parsed = SystemLogicalProcessorInformation.parse(rawBuffer);
        assertEquals(5, parsed.size());

        WindowsSystemLayout layout = new WindowsSystemLayout(parsed);
        assertEquals(4, layout.getCpuInfoMap().size());
        assertEquals(List.of(0, 1, 64, 65), List.copyOf(layout.getCpuInfoMap().keySet()));
    }

    @Test
    void parsesMoreThan64CpusBinaryBufferFixture() {
        // Full group 0 (64 CPUs) + 2 CPUs in group 1 -> total 66 CPUs
        byte[] packageRec = createProcessorRecord(3, (byte) 0, affinity(-1L, 0), affinity(3L, 1));
        byte[] rawCores = new byte[0];
        // Create 64 single-thread cores in Group 0
        for (int i = 0; i < 64; i++) {
            byte[] core = createProcessorRecord(0, (byte) 0, affinity(1L << i, 0));
            rawCores = concat(rawCores, core);
        }
        // Create 2 cores in Group 1
        byte[] g1c0 = createProcessorRecord(0, (byte) 0, affinity(1L, 1));
        byte[] g1c1 = createProcessorRecord(0, (byte) 0, affinity(2L, 1));

        byte[] rawBuffer = concat(packageRec, rawCores, g1c0, g1c1);
        List<SystemLogicalProcessorInformation> parsed = SystemLogicalProcessorInformation.parse(rawBuffer);
        assertEquals(67, parsed.size());

        WindowsSystemLayout layout = new WindowsSystemLayout(parsed);
        assertEquals(66, layout.getCpuInfoMap().size());
        assertTrue(layout.getCpuInfoMap().containsKey(0));
        assertTrue(layout.getCpuInfoMap().containsKey(63));
        assertTrue(layout.getCpuInfoMap().containsKey(64));
        assertTrue(layout.getCpuInfoMap().containsKey(65));
    }

    @Test
    void parsesBit63InBinaryBufferFixture() {
        // Bit 63 set in Group 0 (CPU 63) and Group 1 (CPU 127)
        byte[] packageRec =
                createProcessorRecord(3, (byte) 0, affinity(Long.MIN_VALUE, 0), affinity(Long.MIN_VALUE, 1));
        byte[] core0 = createProcessorRecord(0, (byte) 0, affinity(Long.MIN_VALUE, 0));
        byte[] core1 = createProcessorRecord(0, (byte) 0, affinity(Long.MIN_VALUE, 1));
        byte[] l3Cache = createCacheRecord(
                (byte) 3, (byte) 16, 32768 * 1024, 0, affinity(Long.MIN_VALUE, 0), affinity(Long.MIN_VALUE, 1));

        byte[] rawBuffer = concat(packageRec, core0, core1, l3Cache);
        List<SystemLogicalProcessorInformation> parsed = SystemLogicalProcessorInformation.parse(rawBuffer);
        assertEquals(4, parsed.size());

        WindowsSystemLayout layout = new WindowsSystemLayout(parsed);
        assertEquals(2, layout.getCpuInfoMap().size());
        assertEquals(List.of(63, 127), List.copyOf(layout.getCpuInfoMap().keySet()));
    }

    @Test
    void parsesHeterogeneousPECoreClassification() {
        byte[] packageRec = createProcessorRecord(3, (byte) 0, affinity(3L, 0));
        byte[] pCore = createProcessorRecord(0, (byte) 1, affinity(1L, 0)); // EfficiencyClass = 1 -> P-core
        byte[] eCore = createProcessorRecord(0, (byte) 0, affinity(2L, 0)); // EfficiencyClass = 0 -> E-core

        byte[] rawBuffer = concat(packageRec, pCore, eCore);
        List<SystemLogicalProcessorInformation> parsed = SystemLogicalProcessorInformation.parse(rawBuffer);
        WindowsSystemLayout layout = new WindowsSystemLayout(parsed);

        assertTrue(layout.getCoreInfoMap().get(0).pCore());
        assertFalse(layout.getCoreInfoMap().get(1).pCore());
    }

    @Test
    void parsesInstructionCacheExclusion() {
        byte[] packageRec = createProcessorRecord(3, (byte) 0, affinity(1L, 0));
        byte[] coreRec = createProcessorRecord(0, (byte) 0, affinity(1L, 0));
        byte[] instCache =
                createCacheRecord((byte) 1, (byte) 8, 32 * 1024, 1, affinity(1L, 0)); // type = 1 (INSTRUCTION)
        byte[] dataCache = createCacheRecord((byte) 1, (byte) 8, 32 * 1024, 2, affinity(1L, 0)); // type = 2 (DATA)

        byte[] rawBuffer = concat(packageRec, coreRec, instCache, dataCache);
        List<SystemLogicalProcessorInformation> parsed = SystemLogicalProcessorInformation.parse(rawBuffer);
        assertEquals(4, parsed.size());

        WindowsSystemLayout layout = new WindowsSystemLayout(parsed);
        assertNotNull(layout.getCpuInfoMap().get(0));
    }

    @Test
    void rejectsMalformedBuffers() {
        // Truncated header (< 8 bytes)
        assertThrows(
                IllegalArgumentException.class, () -> SystemLogicalProcessorInformation.parse(new byte[] {0, 0, 0, 0}));

        // Record size < 8
        ByteBuffer bufSmall = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        bufSmall.putInt(0); // relationship
        bufSmall.putInt(4); // size < 8
        assertThrows(IllegalArgumentException.class, () -> SystemLogicalProcessorInformation.parse(bufSmall.array()));

        // Size exceeds buffer limit
        ByteBuffer bufBig = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        bufBig.putInt(0);
        bufBig.putInt(64); // size = 64 > limit 16
        assertThrows(IllegalArgumentException.class, () -> SystemLogicalProcessorInformation.parse(bufBig.array()));

        // GroupCount overflow in payload
        ByteBuffer bufGroupOver = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        bufGroupOver.putInt(0); // PROCESSOR_CORE
        bufGroupOver.putInt(48); // size
        bufGroupOver.put((byte) 0); // flags
        bufGroupOver.put((byte) 0); // eClass
        for (int i = 0; i < 20; i++) {
            bufGroupOver.put((byte) 0);
        }
        bufGroupOver.putShort((short) 10); // groupCount = 10, requires 24 + 160 bytes!
        assertThrows(
                IllegalArgumentException.class, () -> SystemLogicalProcessorInformation.parse(bufGroupOver.array()));
    }

    @Test
    void rejectsMissingPackageOwner() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WindowsSystemLayout(List.of(relationship(Relationship.PROCESSOR_CORE, affinity(1, 0)))));
    }
}
