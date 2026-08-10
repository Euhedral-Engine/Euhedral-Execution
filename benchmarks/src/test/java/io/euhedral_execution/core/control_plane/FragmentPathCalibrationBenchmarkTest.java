package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.BitSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/// Deterministic tests for the fragment path calibration fixture's setup-only helpers.
@Isolated
class FragmentPathCalibrationBenchmarkTest {

    /// Verifies the fixed completion window advances monotonically and rejects overflow.
    @Test
    void advancesCompletionTargetWithoutOverflow() {
        assertEquals(1_048_699L, FragmentPathCalibrationBenchmark.completionTarget(123L));
        assertThrows(
                ArithmeticException.class, () -> FragmentPathCalibrationBenchmark.completionTarget(Long.MAX_VALUE));
    }

    /// Verifies the odd-sample median sorts in place without adding a statistics dependency.
    @Test
    void computesOddMedianInPlace() {
        double[] samples = {9.0, 1.0, 7.0, 3.0, 5.0};

        assertEquals(5.0, FragmentPathCalibrationBenchmark.median(samples));
        assertEquals(1.0, samples[0]);
        assertEquals(9.0, samples[4]);
        assertThrows(
                IllegalArgumentException.class, () -> FragmentPathCalibrationBenchmark.median(new double[] {1.0, 2.0}));
    }

    /// Verifies source availability changes only the number of otherwise identical sources.
    @Test
    void mapsSourceShapeToDeterministicCount() {
        assertEquals(
                4,
                FragmentPathCalibrationBenchmark.sourceCount(
                        FragmentPathCalibrationBenchmark.SourceShape.PLENTIFUL, 4));
        assertEquals(
                1,
                FragmentPathCalibrationBenchmark.sourceCount(FragmentPathCalibrationBenchmark.SourceShape.SCARCE, 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> FragmentPathCalibrationBenchmark.sourceCount(
                        FragmentPathCalibrationBenchmark.SourceShape.SCARCE, 0));
    }

    /// Verifies worker selection is deterministic and does not mutate topology input.
    @Test
    void selectsFirstCandidateCores() {
        BitSet candidates = bits(1, 4, 7);

        assertEquals(bits(1, 4), FragmentPathCalibrationBenchmark.firstCores(candidates, 2));
        assertEquals(bits(1, 4, 7), candidates);
        assertThrows(IllegalArgumentException.class, () -> FragmentPathCalibrationBenchmark.firstCores(candidates, 4));
    }

    /// Verifies benchmark teardown can release the process-wide diagnostic slot for another trial.
    @Test
    void diagnosticLeaseClearsOverrideExactlyOnce() {
        FragmentPathCalibrationBenchmark.DiagnosticLease first = new FragmentPathCalibrationBenchmark.DiagnosticLease(
                FragmentPathCalibrationBenchmark.ForcedMode.DIRECT, 32L);
        first.close();
        first.close();

        FragmentPathCalibrationBenchmark.DiagnosticLease second = new FragmentPathCalibrationBenchmark.DiagnosticLease(
                FragmentPathCalibrationBenchmark.ForcedMode.STAGED, 32L);
        second.close();
    }

    /// Creates a compact deterministic core set.
    private static BitSet bits(int... cores) {
        BitSet set = new BitSet();
        for (int core : cores) {
            set.set(core);
        }
        return set;
    }
}
