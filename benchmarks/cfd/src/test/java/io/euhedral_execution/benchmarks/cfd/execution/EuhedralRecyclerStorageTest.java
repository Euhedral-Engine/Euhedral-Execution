package io.euhedral_execution.benchmarks.cfd.execution;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EuhedralRecyclerStorageTest {
    @Test
    void recyclerStorageIncludesTheEntirePhysicalWorkingSet() {
        assertEquals(0, EuhedralBackend.sourceStorageBytes(512, 0));
        assertTrue(
                EuhedralBackend.sourceStorageBytes(16_777_216, 4) > EuhedralBackend.sourceStorageBytes(2_097_152, 4));
        assertTrue(EuhedralBackend.sourceStorageBytes(16_777_216, 4) >= 16_777_216L * 768);
        assertEquals(3 * (4096 + 8 * (2 + 256)) + 2 * 768, EuhedralBackend.sourceStorageBytes(2, 3));
        assertThrows(IllegalArgumentException.class, () -> EuhedralBackend.sourceStorageBytes(Integer.MAX_VALUE, 1));
    }

    @Test
    void poolCapacityRetainsEveryFrameIncludingPowerOfTwoWorkingSets() {
        for (int count : new int[] {0, 1, 2, 3, 4, 7, 8, 8192, 8193}) {
            var manager = new io.euhedral_execution.core.impl.FrameManager<
                    Object, io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame>(
                    EuhedralBackend.recyclerCapacity(count), 0);
            for (int i = 0; i < count; i++) {
                assertTrue(manager.recycle(new io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame(i, manager)));
            }
            for (int i = 0; i < count; i++) {
                assertNotNull(manager.get(0));
            }
            assertNull(manager.get(0));
        }
    }

    @Test
    void stridedSourceCountsCoverUnevenAndEmptyStreamsExactly() {
        for (int ranges : new int[] {0, 1, 5, 17, 512, 16_777_216}) {
            for (int sources : new int[] {1, 2, 7, 23}) {
                long total = 0;
                for (int i = 0; i < sources; i++) {
                    int count = EuhedralBackend.sourceRangeCount(ranges, sources, i);
                    assertEquals(i >= ranges ? 0 : 1 + (ranges - 1 - i) / sources, count);
                    total += count;
                }
                assertEquals(ranges, total);
            }
        }
    }
}
