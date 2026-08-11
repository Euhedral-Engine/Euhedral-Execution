package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
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

    /// Verifies acquire snapshots preserve stable worker order and omit unselected CPU counters.
    @Test
    void readsSelectedWorkerCountersInStableOrder() {
        PaddedLongAdder counters = new PaddedLongAdder(8, true, true);
        counters.add(5, 17L);
        counters.add(2, 11L);
        counters.add(7, 23L);

        long[] counts = FragmentPathCalibrationBenchmark.workerCounts(counters, new int[] {5, 2});

        assertEquals(17L, counts[0]);
        assertEquals(11L, counts[1]);
    }

    /// Verifies fixed-window worker deltas accumulate and reject regression or shape mismatch.
    @Test
    void accumulatesMonotonicWorkerCompletionDeltas() {
        long[] accumulated = {3L, 5L};

        FragmentPathCalibrationBenchmark.accumulateCompletionDeltas(
                accumulated, new long[] {10L, 20L}, new long[] {17L, 31L});

        assertEquals(10L, accumulated[0]);
        assertEquals(16L, accumulated[1]);
        assertThrows(
                IllegalArgumentException.class,
                () -> FragmentPathCalibrationBenchmark.accumulateCompletionDeltas(
                        accumulated, new long[] {2L}, new long[] {3L}));
        assertThrows(
                IllegalArgumentException.class,
                () -> FragmentPathCalibrationBenchmark.accumulateCompletionDeltas(
                        accumulated, new long[] {10L, 20L}, new long[] {9L, 21L}));
    }

    /// Verifies balanced and dominant completion splits produce the declared decision metrics.
    @Test
    void derivesParticipationMetricsFromRawWorkerDeltas() {
        FragmentPathCalibrationBenchmark.ParticipationMetrics balanced =
                FragmentPathCalibrationBenchmark.participationMetrics(new long[] {500L, 500L}, 225_000L);
        FragmentPathCalibrationBenchmark.ParticipationMetrics dominant =
                FragmentPathCalibrationBenchmark.participationMetrics(new long[] {900L, 100L}, 225_000L);

        assertEquals(0.5, balanced.fractions()[0]);
        assertEquals(0.5, balanced.fractions()[1]);
        assertEquals(0.5, balanced.dominance());
        assertEquals(1.0, balanced.effectiveLanes());
        assertEquals(0.9, dominant.fractions()[0]);
        assertEquals(0.1, dominant.fractions()[1]);
        assertEquals(0.9, dominant.dominance());
        assertThrows(
                IllegalArgumentException.class,
                () -> FragmentPathCalibrationBenchmark.participationMetrics(new long[0], 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> FragmentPathCalibrationBenchmark.participationMetrics(new long[] {1L}, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> FragmentPathCalibrationBenchmark.participationMetrics(
                        new long[] {1L}, 1L, Double.NaN));
    }

    /// Verifies lane estimates use the fixed CPU body or matching phase 1 no-op control.
    @Test
    void selectsWorkloadAndModeLaneCeiling() {
        assertEquals(
                1_000_000_000.0 / 225.0,
                FragmentPathCalibrationBenchmark.singleLaneCeiling(
                        FragmentPathCalibrationBenchmark.Workload.CPU_WORK,
                        FragmentPathCalibrationBenchmark.ForcedMode.DIRECT));
        assertEquals(
                88_797_000.0,
                FragmentPathCalibrationBenchmark.singleLaneCeiling(
                        FragmentPathCalibrationBenchmark.Workload.NO_OP,
                        FragmentPathCalibrationBenchmark.ForcedMode.DIRECT));
        assertEquals(
                35_919_000.0,
                FragmentPathCalibrationBenchmark.singleLaneCeiling(
                        FragmentPathCalibrationBenchmark.Workload.NO_OP,
                        FragmentPathCalibrationBenchmark.ForcedMode.STAGED));
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
