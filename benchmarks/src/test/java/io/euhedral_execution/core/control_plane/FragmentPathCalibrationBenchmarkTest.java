package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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

    /// Verifies the sweep body preserves the fixed anchor and validates its round count.
    @Test
    void appliesDeterministicParameterizedWork() {
        long input = 17L;

        assertEquals(input, FragmentPathCalibrationBenchmark.cpuWork(input, 0));
        assertEquals(
                FragmentPathCalibrationBenchmark.cpuWork(input),
                FragmentPathCalibrationBenchmark.cpuWork(input, FragmentPathCalibrationBenchmark.CPU_WORK_ROUNDS));
        assertThrows(IllegalArgumentException.class, () -> FragmentPathCalibrationBenchmark.cpuWork(input, -1));
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

    /// Verifies CPU-relative labels and explicit counts remain separate physical inputs.
    @Test
    void resolvesCrossoverSourceCountWithoutWorkerInference() {
        assertEquals(32, FragmentPathCalibrationBenchmark.crossoverSourceCount(32, 1, 0));
        assertEquals(8, FragmentPathCalibrationBenchmark.crossoverSourceCount(32, 4, 0));
        assertEquals(1, FragmentPathCalibrationBenchmark.crossoverSourceCount(32, 32, 0));
        assertEquals(1, FragmentPathCalibrationBenchmark.crossoverSourceCount(24, 32, 0));
        assertEquals(7, FragmentPathCalibrationBenchmark.crossoverSourceCount(32, 0, 7));
        assertThrows(
                IllegalArgumentException.class, () -> FragmentPathCalibrationBenchmark.crossoverSourceCount(32, 0, 0));
        assertThrows(
                IllegalArgumentException.class, () -> FragmentPathCalibrationBenchmark.crossoverSourceCount(32, 4, 1));
    }

    /// Verifies core zero is reserved and homogeneous selection cannot admit E cores.
    @Test
    void selectsCrossoverTopologyAfterCoreZeroReservation() {
        BitSet active = bits(0, 1, 2, 8, 9);
        BitSet pCores = bits(0, 1, 2);
        BitSet eCores = bits(8, 9);

        assertEquals(
                bits(1, 2),
                FragmentPathCalibrationBenchmark.crossoverWorkerCores(
                        FragmentPathCalibrationBenchmark.CrossoverTopology.HOMOGENEOUS_P, active, pCores, eCores));
        assertEquals(
                bits(1, 2, 8, 9),
                FragmentPathCalibrationBenchmark.crossoverWorkerCores(
                        FragmentPathCalibrationBenchmark.CrossoverTopology.FULL_MACHINE, active, pCores, eCores));
        assertEquals(bits(0, 1, 2, 8, 9), active);
        assertThrows(
                IllegalStateException.class,
                () -> FragmentPathCalibrationBenchmark.crossoverWorkerCores(
                        FragmentPathCalibrationBenchmark.CrossoverTopology.FULL_MACHINE,
                        bits(1, 2),
                        bits(1, 2),
                        new BitSet()));
    }

    /// Verifies worker selection is deterministic and does not mutate topology input.
    @Test
    void selectsFirstCandidateCores() {
        BitSet candidates = bits(1, 4, 7);

        assertEquals(bits(1, 4), FragmentPathCalibrationBenchmark.firstCores(candidates, 2));
        assertEquals(bits(1, 4, 7), candidates);
        assertThrows(IllegalArgumentException.class, () -> FragmentPathCalibrationBenchmark.firstCores(candidates, 4));
    }

    /// Verifies shared cache masks are rejected while disjoint worker caches are accepted.
    @Test
    void distinguishesPrivateWorkerCaches() {
        assertTrue(FragmentPathCalibrationBenchmark.pairwiseDisjoint(new BitSet[] {bits(0, 1), bits(2, 3), bits(4)}));
        assertFalse(FragmentPathCalibrationBenchmark.pairwiseDisjoint(new BitSet[] {bits(0, 1), bits(1, 2)}));
        assertFalse(FragmentPathCalibrationBenchmark.pairwiseDisjoint(new BitSet[] {new BitSet()}));
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
                () -> FragmentPathCalibrationBenchmark.participationMetrics(new long[] {1L}, 1L, Double.NaN));
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

    /// Verifies acquisition snapshots retain raw events, first-productivity order, and isolation.
    @Test
    void recordsHandleAcquisitionBySourceAndWorker() {
        FragmentPathCalibrationBenchmark.HandleAcquisitionRecorder recorder =
                new FragmentPathCalibrationBenchmark.HandleAcquisitionRecorder(2, new int[] {0}, new int[] {0});

        recorder.recordAcquisition(0, 0, false);
        recorder.recordAcquisition(0, 0, true);
        recorder.recordPullResult(0, 0, 32L);
        recorder.recordAcquisition(1, 0, true);
        recorder.recordPullResult(1, 0, 16L);
        recorder.recordAcquisition(1, 0, true);

        FragmentPathCalibrationBenchmark.HandleSnapshot snapshot = recorder.snapshot();
        assertArrayEquals(new long[] {2L}, snapshot.attempts()[0]);
        assertArrayEquals(new long[] {1L}, snapshot.failures()[0]);
        assertArrayEquals(new long[] {32L}, snapshot.pulledFrames()[0]);
        assertArrayEquals(new long[] {16L}, snapshot.pulledFrames()[1]);
        assertArrayEquals(new long[] {1L}, FragmentPathCalibrationBenchmark.successfulServiceAttempts(snapshot)[0]);
        assertArrayEquals(new long[] {2L}, FragmentPathCalibrationBenchmark.successfulServiceAttempts(snapshot)[1]);
        assertArrayEquals(new long[] {0L, 1L}, snapshot.firstProductiveOrder());

        long[][] isolated = snapshot.pulledFrames();
        isolated[0][0] = 99L;
        assertEquals(32L, snapshot.pulledFrames()[0][0]);
    }

    /// Verifies fixed-point diagnostics normalize only while constructing benchmark reports.
    @Test
    void extractsWorkerLocalAcquisitionContentionDiagnostics() {
        ControlPlaneFragment.AcquireContentionSnapshot[] snapshots = {
            new ControlPlaneFragment.AcquireContentionSnapshot(true, true, 125_000L, 0.125),
            new ControlPlaneFragment.AcquireContentionSnapshot(true, true, 875_000L, 0.875)
        };

        assertArrayEquals(
                new long[] {125_000L, 875_000L},
                FragmentPathCalibrationBenchmark.PathState.acquisitionContentionFixedPoint(snapshots));
        assertArrayEquals(
                new double[] {0.125, 0.875},
                FragmentPathCalibrationBenchmark.PathState.acquisitionContentionNormalized(snapshots));
        assertTrue(snapshots[0].selectionEnabled());
    }

    /// Verifies lifecycle deltas preserve raw matrix shape and reject counter regression.
    @Test
    void computesMonotonicHandleLifecycleDelta() {
        FragmentPathCalibrationBenchmark.HandleSnapshot before = new FragmentPathCalibrationBenchmark.HandleSnapshot(
                new long[][] {{2L, 3L}}, new long[][] {{1L, 0L}}, new long[][] {{32L, 64L}}, new long[] {0L, 1L});
        FragmentPathCalibrationBenchmark.HandleSnapshot after = new FragmentPathCalibrationBenchmark.HandleSnapshot(
                new long[][] {{5L, 9L}}, new long[][] {{1L, 2L}}, new long[][] {{96L, 160L}}, new long[] {0L, 1L});

        FragmentPathCalibrationBenchmark.HandleSnapshot delta =
                FragmentPathCalibrationBenchmark.handleDelta(before, after);

        assertArrayEquals(new long[] {3L, 6L}, delta.attempts()[0]);
        assertArrayEquals(new long[] {0L, 2L}, delta.failures()[0]);
        assertArrayEquals(new long[] {64L, 96L}, delta.pulledFrames()[0]);
        assertArrayEquals(new long[] {3L, 4L}, FragmentPathCalibrationBenchmark.successfulServiceAttempts(delta)[0]);
        assertThrows(IllegalArgumentException.class, () -> FragmentPathCalibrationBenchmark.handleDelta(after, before));
    }

    /// Verifies measurement-only latency deltas and estimates preserve worker alignment.
    @Test
    void computesExistingServiceMetricEstimate() {
        FragmentPathCalibrationBenchmark.ServiceMetricSnapshot before =
                new FragmentPathCalibrationBenchmark.ServiceMetricSnapshot(
                        new long[] {10L, 20L}, new double[] {1_000.0, 4_000.0});
        FragmentPathCalibrationBenchmark.ServiceMetricSnapshot after =
                new FragmentPathCalibrationBenchmark.ServiceMetricSnapshot(
                        new long[] {14L, 25L}, new double[] {1_800.0, 5_500.0});

        FragmentPathCalibrationBenchmark.ServiceMetricSnapshot delta =
                FragmentPathCalibrationBenchmark.serviceMetricDelta(before, after);

        assertArrayEquals(new long[] {4L, 5L}, delta.counts());
        assertArrayEquals(new double[] {800.0, 1_500.0}, delta.totals());
        assertArrayEquals(new double[] {200.0, 300.0}, FragmentPathCalibrationBenchmark.serviceEstimates(delta));

        long[] isolatedCounts = delta.counts();
        isolatedCounts[0] = 99L;
        assertEquals(4L, delta.counts()[0]);
        assertThrows(
                IllegalArgumentException.class,
                () -> FragmentPathCalibrationBenchmark.serviceMetricDelta(after, before));
        assertTrue(Double.isNaN(
                FragmentPathCalibrationBenchmark.serviceEstimates(
                        new FragmentPathCalibrationBenchmark.ServiceMetricSnapshot(
                                new long[] {0L}, new double[] {0.0}))[0]));
    }

    /// Verifies sparse body timing retains worker alignment and rejects invalid deltas or samples.
    @Test
    void computesSparseBodyTimingDeltasAndEstimates() {
        FragmentPathCalibrationBenchmark.BodyTimingSnapshot before =
                new FragmentPathCalibrationBenchmark.BodyTimingSnapshot(
                        new long[] {10L, 20L}, new long[] {1_000L, 4_000L});
        FragmentPathCalibrationBenchmark.BodyTimingSnapshot after =
                new FragmentPathCalibrationBenchmark.BodyTimingSnapshot(
                        new long[] {14L, 25L}, new long[] {1_800L, 5_500L});

        FragmentPathCalibrationBenchmark.BodyTimingSnapshot delta =
                FragmentPathCalibrationBenchmark.bodyTimingDelta(before, after);

        assertArrayEquals(new long[] {4L, 5L}, delta.counts());
        assertArrayEquals(new long[] {800L, 1_500L}, delta.elapsedNanos());
        assertArrayEquals(new double[] {200.0, 300.0}, FragmentPathCalibrationBenchmark.bodyTimingEstimates(delta));
        assertThrows(
                IllegalArgumentException.class, () -> FragmentPathCalibrationBenchmark.bodyTimingDelta(after, before));
        assertThrows(
                IllegalArgumentException.class,
                () -> FragmentPathCalibrationBenchmark.bodyTimingEstimates(
                        new FragmentPathCalibrationBenchmark.BodyTimingSnapshot(new long[] {0L}, new long[] {0L})));
    }

    /// Verifies retained extrema and even-sized medians preserve raw fork-worker interpretation.
    @Test
    void constructsRetainedBodyTimingRange() {
        double[] estimates = {84.0, 82.0, 88.0, 86.0};

        FragmentPathCalibrationBenchmark.RetainedRange range =
                FragmentPathCalibrationBenchmark.retainedRange(estimates);

        assertEquals(82.0, range.minimum());
        assertEquals(88.0, range.maximum());
        assertEquals(85.0, FragmentPathCalibrationBenchmark.bodyTimingMedian(estimates));
        assertArrayEquals(new double[] {84.0, 82.0, 88.0, 86.0}, estimates);
    }

    /// Verifies the declared margin requires all retained 80-round values below all 96-round values.
    @Test
    void appliesBodyTimingSeparationMargin() {
        double[] rounds24 = {21.0, 22.0};
        double[] rounds80 = {70.0, 72.0};
        double[] separated96 = {77.0, 79.0};
        double[] overlapping96 = {76.9, 79.0};

        assertTrue(FragmentPathCalibrationBenchmark.bodyTimingSeparationPassed(rounds24, rounds80, separated96));
        assertEquals(
                false, FragmentPathCalibrationBenchmark.bodyTimingSeparationPassed(rounds24, rounds80, overlapping96));
    }

    /// Verifies the fixed stability and four-group neutrality formulas at their exact bounds.
    @Test
    void appliesBodyTimingStabilityAndNeutralityBounds() {
        assertTrue(FragmentPathCalibrationBenchmark.bodyTimingStabilityPassed(new double[] {95.0, 100.0, 105.0}));
        assertEquals(
                false, FragmentPathCalibrationBenchmark.bodyTimingStabilityPassed(new double[] {89.0, 100.0, 111.0}));
        assertTrue(FragmentPathCalibrationBenchmark.bodyTimingNeutralityPassed(
                new double[][] {{80.0, 82.0}, {81.0, 83.0}, {84.0, 86.0}, {85.0, 87.0}}));
        assertEquals(false, FragmentPathCalibrationBenchmark.bodyTimingNeutralityPassed(new double[][] {
            {80.0}, {82.0}, {84.0}, {85.1}
        }));
        assertThrows(
                IllegalArgumentException.class,
                () -> FragmentPathCalibrationBenchmark.bodyTimingNeutralityPassed(new double[][] {{80.0}}));
    }

    /// Verifies source identity and lifecycle report formatting remain deterministic.
    @Test
    void formatsHandleLifecycleEvidenceDeterministically() {
        FragmentPathCalibrationBenchmark.HandleSnapshot snapshot = new FragmentPathCalibrationBenchmark.HandleSnapshot(
                new long[][] {{2L}}, new long[][] {{0L}}, new long[][] {{32L}}, new long[] {0L});

        assertArrayEquals(new int[] {0, 1}, FragmentPathCalibrationBenchmark.sourceOrdinals(2));
        assertEquals(
                "[{attempts=[[2]], failures=[[0]], successfulServiceAttempts=[[2]], "
                        + "pulledFrames=[[32]], firstProductiveOrder=[0]}]",
                FragmentPathCalibrationBenchmark.formatHandleSnapshots(List.of(snapshot)));
        assertThrows(IllegalArgumentException.class, () -> FragmentPathCalibrationBenchmark.sourceOrdinals(-1));
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

        FragmentPathCalibrationBenchmark.DiagnosticLease polling =
                new FragmentPathCalibrationBenchmark.DiagnosticLease(bits(2));
        polling.close();
    }

    /// Verifies the generalized idle fixture retains exact productive, live, and polling counts.
    @Test
    void retainsIdleDiscoveryFixtureCounts() {
        FragmentPathCalibrationBenchmark.IdleFixture fixture =
                new FragmentPathCalibrationBenchmark.IdleFixture(2, 1, 1);

        assertEquals(2, fixture.productiveHandles());
        assertEquals(3, fixture.liveHandles());
        assertEquals(1, fixture.activePollingWorkers());
        assertThrows(IllegalArgumentException.class, () -> new FragmentPathCalibrationBenchmark.IdleFixture(1, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new FragmentPathCalibrationBenchmark.IdleFixture(1, 0, -1));
    }

    /// Verifies the five normal rows remain fixed before JMH execution.
    @Test
    void retainsPredeclaredProductionPolicyCases() {
        FragmentPathCalibrationBenchmark.NormalPolicyCase guard =
                FragmentPathCalibrationBenchmark.NormalPolicyCase.SCARCE_88;
        FragmentPathCalibrationBenchmark.ProductionEstimatorCase direct =
                FragmentPathCalibrationBenchmark.ProductionEstimatorCase.PLENTIFUL_DIRECT;
        FragmentPathCalibrationBenchmark.ProductionEstimatorCase staged =
                FragmentPathCalibrationBenchmark.ProductionEstimatorCase.SCARCE_STAGED;

        assertEquals(FragmentPathCalibrationBenchmark.SourceShape.SCARCE, guard.sourceShape);
        assertEquals(88, guard.workRounds);
        assertEquals(FragmentPathCalibrationBenchmark.SourceShape.PLENTIFUL, direct.sourceShape);
        assertEquals(FragmentPathCalibrationBenchmark.ForcedMode.DIRECT, direct.mode);
        assertEquals(FragmentPathCalibrationBenchmark.SourceShape.SCARCE, staged.sourceShape);
        assertEquals(FragmentPathCalibrationBenchmark.ForcedMode.STAGED, staged.mode);
        assertEquals(5, FragmentPathCalibrationBenchmark.NormalPolicyCase.values().length);
    }

    /// Verifies production estimator timing remains opt-in for a forced diagnostic lease.
    @Test
    void forcedProductionSamplingIsExplicit() {
        try (FragmentPathCalibrationBenchmark.DiagnosticLease ignored =
                new FragmentPathCalibrationBenchmark.DiagnosticLease(
                        FragmentPathCalibrationBenchmark.ForcedMode.DIRECT, 32L)) {
            assertFalse(new FragmentControlPolicy().bodyCostSamplingEnabled());
        }

        try (FragmentPathCalibrationBenchmark.DiagnosticLease ignored =
                new FragmentPathCalibrationBenchmark.DiagnosticLease(
                        FragmentPathCalibrationBenchmark.ForcedMode.STAGED, 32L, true)) {
            assertTrue(new FragmentControlPolicy().bodyCostSamplingEnabled());
        }
    }

    /// Verifies the three Phase 8 rows retain their declared live and productive handle counts.
    @Test
    void retainsProductiveOpportunityFixtures() {
        assertEquals(2, FragmentPathCalibrationBenchmark.OpportunityFixture.TWO_PRODUCTIVE_HANDLES.liveHandles);
        assertEquals(2, FragmentPathCalibrationBenchmark.OpportunityFixture.TWO_PRODUCTIVE_HANDLES.productiveHandles);
        assertEquals(1, FragmentPathCalibrationBenchmark.OpportunityFixture.ONE_PRODUCTIVE_HANDLE.liveHandles);
        assertEquals(1, FragmentPathCalibrationBenchmark.OpportunityFixture.ONE_PRODUCTIVE_HANDLE.productiveHandles);
        assertEquals(2, FragmentPathCalibrationBenchmark.OpportunityFixture.TWO_LIVE_ONE_PRODUCTIVE.liveHandles);
        assertEquals(1, FragmentPathCalibrationBenchmark.OpportunityFixture.TWO_LIVE_ONE_PRODUCTIVE.productiveHandles);
        assertEquals(3, FragmentPathCalibrationBenchmark.OpportunityFixture.values().length);
    }

    /// Verifies overhead controls keep productive success and real empty misses separate.
    @Test
    void retainsProductiveSensorOverheadCases() {
        FragmentPathCalibrationBenchmark.ProductiveSensorOverheadCase productive =
                FragmentPathCalibrationBenchmark.ProductiveSensorOverheadCase.PRODUCTIVE_FAST;
        FragmentPathCalibrationBenchmark.ProductiveSensorOverheadCase empty =
                FragmentPathCalibrationBenchmark.ProductiveSensorOverheadCase.EMPTY_MISS;

        assertEquals(
                FragmentPathCalibrationBenchmark.OpportunityFixture.TWO_PRODUCTIVE_HANDLES,
                productive.opportunityFixture);
        assertEquals(FragmentPathCalibrationBenchmark.ForcedMode.DIRECT, productive.mode);
        assertEquals(
                FragmentPathCalibrationBenchmark.OpportunityFixture.TWO_LIVE_ONE_PRODUCTIVE, empty.opportunityFixture);
        assertEquals(FragmentPathCalibrationBenchmark.ForcedMode.STAGED, empty.mode);
        assertEquals(2, FragmentPathCalibrationBenchmark.ProductiveObservation.values().length);
    }

    /// Verifies the accepted integration confirmation remains limited to the four declared rows.
    @Test
    void retainsProductiveNormalPolicyCases() {
        FragmentPathCalibrationBenchmark.ProductivePolicyCase plentiful =
                FragmentPathCalibrationBenchmark.ProductivePolicyCase.TWO_PRODUCTIVE_EXPENSIVE;
        FragmentPathCalibrationBenchmark.ProductivePolicyCase scarce =
                FragmentPathCalibrationBenchmark.ProductivePolicyCase.ONE_PRODUCTIVE_EXPENSIVE;
        FragmentPathCalibrationBenchmark.ProductivePolicyCase mixedExpensive =
                FragmentPathCalibrationBenchmark.ProductivePolicyCase.TWO_LIVE_ONE_PRODUCTIVE_EXPENSIVE;
        FragmentPathCalibrationBenchmark.ProductivePolicyCase mixedCheap =
                FragmentPathCalibrationBenchmark.ProductivePolicyCase.TWO_LIVE_ONE_PRODUCTIVE_CHEAP;

        assertEquals(
                FragmentPathCalibrationBenchmark.OpportunityFixture.TWO_PRODUCTIVE_HANDLES,
                plentiful.opportunityFixture);
        assertEquals(FragmentPathCalibrationBenchmark.ForcedMode.DIRECT, plentiful.expectedMode);
        assertEquals(
                FragmentPathCalibrationBenchmark.OpportunityFixture.ONE_PRODUCTIVE_HANDLE, scarce.opportunityFixture);
        assertEquals(FragmentPathCalibrationBenchmark.ForcedMode.STAGED, scarce.expectedMode);
        assertEquals(512, mixedExpensive.workRounds);
        assertEquals(FragmentPathCalibrationBenchmark.ForcedMode.STAGED, mixedExpensive.expectedMode);
        assertEquals(24, mixedCheap.workRounds);
        assertEquals(FragmentPathCalibrationBenchmark.ForcedMode.DIRECT, mixedCheap.expectedMode);
        assertEquals(4, FragmentPathCalibrationBenchmark.ProductivePolicyCase.values().length);
    }

    /// Verifies an empty production queue remains live while pulls and requests produce no frames.
    @Test
    void emptyQueueSourceRemainsLiveAndNonproductive() {
        QueueIngestSink sink = new QueueIngestSink();
        AtomicLong pushed = new AtomicLong();
        sink.getDelegate().addDownstream(receiver(pushed));

        long pulled = sink.getDelegate().pull(frame -> pushed.incrementAndGet(), frame -> false, 32L);
        sink.getDelegate().request(32L);

        assertEquals(0L, pulled);
        assertEquals(0L, pushed.get());
        assertEquals(0L, sink.size());
        assertEquals(32L, sink.getDemand());
        assertFalse(sink.isComplete());

        sink.complete();
        assertTrue(sink.isComplete());
    }

    /// Verifies the normal benchmark rejects a guard-mode change without constraining estimator shape.
    @Test
    void validatesResolvedAndGuardPolicySnapshots() {
        FragmentPathCalibrationBenchmark.NormalPolicyState state =
                new FragmentPathCalibrationBenchmark.NormalPolicyState();
        state.policyCase = FragmentPathCalibrationBenchmark.NormalPolicyCase.SCARCE_88;
        ControlPlaneFragment.FragmentPolicySnapshot guarded = new ControlPlaneFragment.FragmentPolicySnapshot(
                FragmentControlPolicy.Mode.DIRECT, FragmentControlPolicy.BODY_COST_MIN_HISTORY, 92.0, 100.0, 32L, 1L);

        state.validatePolicySnapshots(
                org.openjdk.jmh.runner.IterationType.MEASUREMENT,
                new ControlPlaneFragment.FragmentPolicySnapshot[] {guarded});

        ControlPlaneFragment.FragmentPolicySnapshot staged = new ControlPlaneFragment.FragmentPolicySnapshot(
                FragmentControlPolicy.Mode.STAGED, FragmentControlPolicy.BODY_COST_MIN_HISTORY, 92.0, 100.0, 32L, 1L);
        state.validatePolicySnapshots(
                org.openjdk.jmh.runner.IterationType.MEASUREMENT,
                new ControlPlaneFragment.FragmentPolicySnapshot[] {staged});

        assertTrue(state.policyValidationFailure().contains("wrong resolved or guard mode"));
    }

    /// Creates a receiver that counts synchronously pushed frames for source-contract tests.
    private static LatticeReceiver receiver(AtomicLong pushed) {
        return new LatticeReceiver() {
            @Override
            public void addUpstream(io.euhedral_execution.core.generics.LatticeSource upstream) {}

            @Override
            public void push(io.euhedral_execution.core.frames.AbstractFrame frame) {
                pushed.incrementAndGet();
            }

            @Override
            public void onComplete() {}

            @Override
            public void onError(Throwable e) {
                throw new AssertionError(e);
            }
        };
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
