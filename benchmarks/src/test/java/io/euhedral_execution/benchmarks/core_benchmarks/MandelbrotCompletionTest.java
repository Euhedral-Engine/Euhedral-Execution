package io.euhedral_execution.benchmarks.core_benchmarks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import org.junit.jupiter.api.Test;

class MandelbrotCompletionTest {

    private static PaddedLongAdder completed(long operations) {
        PaddedLongAdder counters = new PaddedLongAdder(1, true, true);
        counters.add(0, operations);
        return counters;
    }

    @Test
    void acceptsExactlyTheExpectedOperationCount() {
        assertDoesNotThrow(() -> MandelbrotCompletion.verify(completed(16), 16));
    }

    @Test
    void rejectsIncompleteOperationCount() {
        assertThrows(IllegalStateException.class, () -> MandelbrotCompletion.verify(completed(15), 16));
    }

    @Test
    void rejectsCompletionBeyondExpectedOperationCount() {
        assertThrows(IllegalStateException.class, () -> MandelbrotCompletion.verify(completed(17), 16));
    }
}
