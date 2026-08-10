package io.euhedral_execution.benchmarks.core_benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

class HighContentionThroughputTest {

    /// Verifies that a hybrid topology reserves its highest active E-core.
    @Test
    void prefersHighestActiveECore() {
        BitSet active = bits(1, 3, 5, 8);
        BitSet eCores = bits(5, 8, 10);

        assertEquals(8, HighContentionThroughput.selectHarnessCore(eCores, active));
    }

    /// Verifies homogeneous topologies reserve their highest active core.
    @Test
    void fallsBackToHighestActiveCore() {
        assertEquals(7, HighContentionThroughput.selectHarnessCore(new BitSet(), bits(0, 2, 7)));
    }

    /// Verifies a one-core host keeps every CPU available to the lattice.
    @Test
    void declinesIsolationOnOneCore() {
        assertEquals(-1, HighContentionThroughput.selectHarnessCore(bits(4), bits(4)));
    }

    /// Verifies all logical siblings of the reserved core are removed.
    @Test
    void removesCompleteHarnessCoreFromWorkerSet() {
        assertEquals(bits(0, 1, 4), HighContentionThroughput.workerCpuSet(bits(0, 1, 2, 3, 4), bits(2, 3)));
    }

    /// Verifies isolation cannot silently leave the lattice without a worker CPU.
    @Test
    void rejectsEmptyWorkerSet() {
        assertThrows(IllegalStateException.class, () -> HighContentionThroughput.workerCpuSet(bits(2, 3), bits(2, 3)));
    }

    /// Verifies each invocation advances the absolute target by the fixed operation count.
    @Test
    void advancesMonotonicCompletionTarget() {
        assertEquals(32_000_123L, HighContentionThroughput.completionTarget(123L));
        assertThrows(ArithmeticException.class, () -> HighContentionThroughput.completionTarget(Long.MAX_VALUE));
    }

    /// Verifies waits succeed at the target and fail rather than returning partial work.
    @Test
    void completionWaitIsBounded() {
        PaddedLongAdder counters = new PaddedLongAdder(1, true, false);
        counters.add(0, 32_000_123L);
        HighContentionThroughput.await(counters, 32_000_123L, 1_000_000L);

        assertThrows(
                IllegalStateException.class,
                () -> HighContentionThroughput.await(new PaddedLongAdder(1), 32_000_000L, 0L));
    }

    /// Creates a compact BitSet fixture from the supplied indices.
    private static BitSet bits(int... indices) {
        BitSet set = new BitSet();
        for (int index : indices) {
            set.set(index);
        }
        return set;
    }
}
