package io.euhedral_execution.benchmarks.cfd.execution;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EuhedralRecyclerStorageTest {
    @Test
    void recyclerStorageDoesNotGrowWithMillionsOfLogicalRanges() {
        assertEquals(0, EuhedralBackend.sourceStorageBytes(512, 0));
        assertEquals(
                EuhedralBackend.sourceStorageBytes(2_097_152, 4), EuhedralBackend.sourceStorageBytes(16_777_216, 4));
    }
}
