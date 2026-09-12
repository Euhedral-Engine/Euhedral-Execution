package calibration.comparisons.schema;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.config.ComparisonOptions;
import calibration.config.TrialConfig;
import calibration.infra.Constants;
import calibration.statistics.ComparisonOutcome;
import calibration.statistics.DecisionGrid;
import calibration.statistics.VectorField;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.ScalarSummary;
import calibration.statistics.iteration.TransitionAnalysis;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparisonSchemaTest {

    @Test
    void testComparisonRequestValidation() {
        RunReference baseline = RunReference.of("/path/to/baseline", "Baseline Run");
        RunReference cand1 = RunReference.of("/path/to/cand1", "Candidate 1");
        RunReference cand2 = RunReference.of("/path/to/cand2", "Candidate 2");

        // Requires baseline
        assertThrows(NullPointerException.class, () -> new ComparisonRequest(null, List.of(cand1)));

        // Requires at least one candidate
        assertThrows(NullPointerException.class, () -> new ComparisonRequest(baseline, null));
        assertThrows(IllegalArgumentException.class, () -> new ComparisonRequest(baseline, List.of()));

        // Duplicate candidates rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComparisonRequest(baseline, List.of(cand1, RunReference.of("/path/to/cand1", "Other"))));

        // Valid request with multiple candidates succeeds
        ComparisonRequest request = new ComparisonRequest(baseline, List.of(cand1, cand2));
        assertEquals(baseline, request.baseline());
        assertEquals(2, request.candidates().size());
        assertEquals(ComparisonOptions.DEFAULT, request.options());
    }

    @Test
    void testRunReference() {
        assertThrows(NullPointerException.class, () -> new RunReference(null, "label"));
        assertThrows(IllegalArgumentException.class, () -> new RunReference("   ", "label"));

        RunReference ref = RunReference.of("/out/dir");
        assertEquals("/out/dir", ref.path());
        assertNull(ref.label());

        RunReference labeledRef = RunReference.of("/out/dir", "Trial A");
        assertEquals("/out/dir", labeledRef.path());
        assertEquals("Trial A", labeledRef.label());
    }

    @Test
    void testRunIdentity() {
        assertThrows(NullPointerException.class, () -> new RunIdentity(null, "name", "grp", 0, null, "/src"));
        assertThrows(IllegalArgumentException.class, () -> new RunIdentity("  ", "name", "grp", 0, null, "/src"));
        assertThrows(NullPointerException.class, () -> new RunIdentity("t1", "name", "grp", 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RunIdentity("t1", "name", "grp", 0, null, "  "));

        RunIdentity id = new RunIdentity("trial-1", "Trial One", "group-a", 2, 1, "/path/to/run");
        assertEquals("trial-1", id.trialId());
        assertEquals("Trial One", id.trialName());
        assertEquals("group-a", id.trialGroup());
        assertEquals(2, id.repeatIndex());
        assertEquals(1, id.forkIndex());
        assertEquals("/path/to/run", id.sourcePath());
    }

    @Test
    void testThroughputResultPreservesRawEvidence() {
        assertThrows(NullPointerException.class, () -> new ThroughputResult(100.0, 2.0, null, List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class, () -> new ThroughputResult(100.0, 2.0, "  ", List.of(), List.of()));

        List<Double> forkScores = List.of(98.5, 101.2, 100.3);
        List<Double> iterScores = List.of(97.0, 99.0, 102.0, 101.0, 101.5, 99.5);

        ThroughputResult result = new ThroughputResult(100.0, 1.35, "ops/s", forkScores, iterScores);
        assertEquals(100.0, result.score());
        assertEquals(1.35, result.scoreError());
        assertEquals("ops/s", result.scoreUnit());
        assertEquals(forkScores, result.forkScores());
        assertEquals(iterScores, result.iterationScores());

        // Verify ThroughputResult has no latency fields by reflection
        for (var field : ThroughputResult.class.getDeclaredFields()) {
            assertFalse(
                    field.getName().toLowerCase().contains("latency"),
                    "ThroughputResult must not contain latency field: " + field.getName());
        }
    }

    @Test
    void testRunArtifacts() {
        RunArtifacts artifacts = RunArtifacts.standard("/runs/trial_1_repeat_0");
        assertEquals("/runs/trial_1_repeat_0", artifacts.rootDirectory());
        assertEquals("/runs/trial_1_repeat_0/trial_config.json", artifacts.trialConfigPath());
        assertEquals("/runs/trial_1_repeat_0/" + Constants.RAW_OBSERVATION_TSV, artifacts.rawObservationsPath());
        assertEquals(
                "/runs/trial_1_repeat_0/" + Constants.RAW_OBSERVATION_CHECKSUM,
                artifacts.rawObservationsChecksumPath());
        assertEquals("/runs/trial_1_repeat_0/" + Constants.STATISTICS_TSV, artifacts.statisticsPath());
        assertEquals("/runs/trial_1_repeat_0/" + Constants.STATISTICS_CHECKSUM, artifacts.statisticsChecksumPath());
        assertEquals("/runs/trial_1_repeat_0/" + Constants.OCCUPANCY_TSV, artifacts.occupancyPath());
        assertEquals("/runs/trial_1_repeat_0/" + Constants.TRANSITIONS_TSV, artifacts.transitionsPath());
        assertEquals("/runs/trial_1_repeat_0/" + Constants.VECTOR_FIELDS_TSV, artifacts.vectorFieldsPath());
        assertEquals("/runs/trial_1_repeat_0/" + Constants.CORRELATIONS_TSV, artifacts.correlationsPath());
        assertEquals("/runs/trial_1_repeat_0/" + Constants.BENCHMARK_OUTPUT_LOG, artifacts.benchmarkOutputPath());
    }

    @Test
    void testCompletedRunPreservesTrialConfigAndData() {
        RunIdentity id = new RunIdentity("t1", "Trial 1", "grp", 0, null, "/path/to/t1");
        TrialConfig trialConfig = new TrialConfig(1, 1, 1, null, "test-profile");
        ThroughputResult throughput = ThroughputResult.of(12345.67, 89.0, "ops/s");
        RunArtifacts artifacts = RunArtifacts.standard("/path/to/t1");
        List<List<CoreIterationResult>> iterations = List.of(List.of(CoreIterationResult.EMPTY));

        CompletedRun completedRun = new CompletedRun(id, trialConfig, throughput, iterations, artifacts);

        assertSame(id, completedRun.identity());
        assertSame(trialConfig, completedRun.trialConfig());
        assertSame(throughput, completedRun.throughput());
        assertSame(artifacts, completedRun.artifacts());
        assertEquals(1, completedRun.iterations().size());
        assertEquals(1, completedRun.iterations().getFirst().size());
    }

    @Test
    void testConfigurationDifference() {
        assertThrows(
                NullPointerException.class,
                () -> new ConfigurationDifference(null, null, null, DifferenceCategory.POLICY));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConfigurationDifference("  ", null, null, DifferenceCategory.POLICY));
        assertThrows(
                NullPointerException.class, () -> new ConfigurationDifference("/parallelSources", null, null, null));

        ConfigurationDifference diff = new ConfigurationDifference(
                "/calibrationConfig/parallelSources", new IntNode(2), new IntNode(4), DifferenceCategory.WORKLOAD);

        assertEquals("/calibrationConfig/parallelSources", diff.path());
        assertEquals(new IntNode(2), diff.baselineValue());
        assertEquals(new IntNode(4), diff.candidateValue());
        assertEquals(DifferenceCategory.WORKLOAD, diff.category());

        // Verify all categories
        for (DifferenceCategory category : DifferenceCategory.values()) {
            ConfigurationDifference cd = new ConfigurationDifference("/test", null, null, category);
            assertEquals(category, cd.category());
        }
    }

    @Test
    void testComparisonCompatibility() {
        ComparisonCompatibility compatible = ComparisonCompatibility.compatible();
        assertEquals(CompatibilityStatus.COMPATIBLE, compatible.status());
        assertTrue(compatible.isComparable());
        assertTrue(compatible.differences().isEmpty());
        assertTrue(compatible.reasons().isEmpty());

        ConfigurationDifference diff = new ConfigurationDifference(
                "/calibrationConfig/observeIdleDecision",
                TextNode.valueOf("true"),
                TextNode.valueOf("false"),
                DifferenceCategory.OBSERVATION);

        ComparisonCompatibility partial = ComparisonCompatibility.partial(
                List.of(diff), List.of("Observation toggles differ, diagnostics partially missing"));
        assertEquals(CompatibilityStatus.PARTIAL, partial.status());
        assertTrue(partial.isComparable());
        assertEquals(1, partial.differences().size());
        assertEquals(1, partial.reasons().size());

        ComparisonCompatibility incompatible = ComparisonCompatibility.incompatible(
                List.of(new ConfigurationDifference(
                        "/workUnits", new IntNode(10), new IntNode(100), DifferenceCategory.WORKLOAD)),
                List.of("Synthetic work units mismatch makes benchmark runs incompatible"));
        assertEquals(CompatibilityStatus.INCOMPATIBLE, incompatible.status());
        assertFalse(incompatible.isComparable());
    }

    @Test
    void testPerformanceComparison() {
        ThroughputResult baseThroughput = ThroughputResult.of(100.0, 1.0, "ops/s");
        ThroughputResult candThroughput = ThroughputResult.of(120.0, 1.0, "ops/s");
        ScalarSummary baseSummary = ScalarSummary.of(100.0, 100.0);
        ScalarSummary candSummary = ScalarSummary.of(120.0, 120.0);

        PerformanceComparison perf = new PerformanceComparison(
                baseThroughput, candThroughput, 20.0, 20.0, baseSummary, candSummary, ComparisonOutcome.B_BETTER);

        assertSame(baseThroughput, perf.baseline());
        assertSame(candThroughput, perf.candidate());
        assertEquals(20.0, perf.absoluteDelta());
        assertEquals(20.0, perf.relativeDeltaPercent());
        assertEquals(ComparisonOutcome.B_BETTER, perf.outcome());
    }

    @Test
    void testScalarComparison() {
        ScalarSummary base = ScalarSummary.of(10.0, 20.0, 30.0);
        ScalarSummary cand = ScalarSummary.of(15.0, 25.0, 35.0);

        ScalarComparison sc = new ScalarComparison(
                base,
                cand,
                5.0, // mean delta
                5.0, // median delta
                0.0, // variance delta
                0.0, // std dev delta
                -0.1, // cv delta
                5.0, // min delta
                5.0, // max delta
                5.0, // p25 delta
                5.0, // p50 delta
                5.0, // p75 delta
                5.0, // p95 delta
                0.0, // iqr delta
                -0.05, // normalized iqr delta
                -0.02 // p95 to p50 delta
                );

        assertSame(base, sc.baseline());
        assertSame(cand, sc.candidate());
        assertEquals(5.0, sc.meanDelta());
        assertEquals(5.0, sc.medianDelta());
        assertEquals(0.0, sc.varianceDelta());
        assertEquals(0.0, sc.standardDeviationDelta());
        assertEquals(-0.1, sc.cvDelta());
        assertEquals(5.0, sc.minDelta());
        assertEquals(5.0, sc.maxDelta());
        assertEquals(5.0, sc.p25Delta());
        assertEquals(5.0, sc.p50Delta());
        assertEquals(5.0, sc.p75Delta());
        assertEquals(5.0, sc.p95Delta());
        assertEquals(0.0, sc.iqrDelta());
        assertEquals(-0.05, sc.normalizedIqrDelta());
        assertEquals(-0.02, sc.p95ToP50RatioDelta());
    }

    @Test
    void testOccupancyComparisonAcceptsFull2x5Structures() {
        BranchOccupancyResult base = BranchOccupancyResult.EMPTY;
        BranchOccupancyResult cand = BranchOccupancyResult.EMPTY;

        long[][] countDeltas = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        double[][] probDeltas = new double[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        countDeltas[0][0] = 50L;
        probDeltas[0][0] = 0.5;

        OccupancyComparison occ = new OccupancyComparison(
                base,
                cand,
                countDeltas,
                probDeltas,
                0.2, // contention centroid delta
                -0.1, // body centroid delta
                0.2236, // centroid distance
                0.05, // contention variance delta
                0.02, // body variance delta
                0.01, // covariance delta
                0.03, // radius delta
                0.25 // total variation distance
                );

        assertSame(base, occ.baseline());
        assertSame(cand, occ.candidate());
        assertEquals(50L, occ.countDeltas()[0][0]);
        assertEquals(0.5, occ.probabilityDeltas()[0][0]);
        assertEquals(0.2, occ.contentionCentroidDelta());
        assertEquals(-0.1, occ.bodyCentroidDelta());
        assertEquals(0.2236, occ.centroidDistance());
        assertEquals(0.05, occ.contentionVarianceDelta());
        assertEquals(0.02, occ.bodyVarianceDelta());
        assertEquals(0.01, occ.covarianceDelta());
        assertEquals(0.03, occ.radiusDelta());
        assertEquals(0.25, occ.totalVariationDistance());
    }

    @Test
    void testTransitionComparisonAcceptsFull10x10Structures() {
        TransitionAnalysis base = TransitionAnalysis.compute(new int[] {0, 1, 2, 0});
        TransitionAnalysis cand = TransitionAnalysis.compute(new int[] {0, 2, 1, 0});

        long[][] countDeltas = new long[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];
        double[][] probDeltas = new double[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];
        double[] selfRateDeltas = new double[DecisionGrid.TOTAL_STATES];
        int[] candDominantStates = new int[DecisionGrid.TOTAL_STATES];
        double[] domProbDeltas = new double[DecisionGrid.TOTAL_STATES];
        double[][] oscDeltas = new double[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];

        countDeltas[0][1] = -1L;
        countDeltas[0][2] = 1L;

        TransitionComparison tc = new TransitionComparison(
                base, cand, countDeltas, probDeltas, selfRateDeltas, candDominantStates, domProbDeltas, oscDeltas);

        assertSame(base, tc.baseline());
        assertSame(cand, tc.candidate());
        assertEquals(-1L, tc.countDeltas()[0][1]);
        assertEquals(1L, tc.countDeltas()[0][2]);
        assertEquals(DecisionGrid.TOTAL_STATES, tc.countDeltas().length);
        assertEquals(DecisionGrid.TOTAL_STATES, tc.probabilityDeltas().length);
        assertEquals(DecisionGrid.TOTAL_STATES, tc.selfTransitionRateDeltas().length);
        assertEquals(DecisionGrid.TOTAL_STATES, tc.candidateDominantOutgoingStates().length);
        assertEquals(DecisionGrid.TOTAL_STATES, tc.dominantOutgoingProbabilityDeltas().length);
        assertEquals(DecisionGrid.TOTAL_STATES, tc.oscillationScoreDeltas().length);
    }

    @Test
    void testVectorComparisonPreservesAll10Cells() {
        VectorField base = VectorField.compute(new int[] {0, 1, 2, 0});
        VectorField cand = VectorField.compute(new int[] {0, 2, 1, 0});

        VectorCellComparison[][] cells =
                new VectorCellComparison[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        for (int c = 0; c < DecisionGrid.CONTENTION_OUTCOMES; c++) {
            for (int b = 0; b < DecisionGrid.BODY_OUTCOMES; b++) {
                cells[c][b] = new VectorCellComparison(c, b, base.cell(c, b), cand.cell(c, b), 10L, 0.1, -0.2, 0.2236);
            }
        }

        VectorFieldComparison vfc = new VectorFieldComparison(base, cand, cells);
        assertSame(base, vfc.baseline());
        assertSame(cand, vfc.candidate());

        // Check grid coordinates and state index access
        for (int c = 0; c < DecisionGrid.CONTENTION_OUTCOMES; c++) {
            for (int b = 0; b < DecisionGrid.BODY_OUTCOMES; b++) {
                VectorCellComparison cell = vfc.cell(c, b);
                assertNotNull(cell);
                assertEquals(c, cell.contentionBand());
                assertEquals(b, cell.bodyBand());
                assertEquals(10L, cell.transitionCountDelta());

                int state = c * DecisionGrid.BODY_OUTCOMES + b;
                assertSame(cell, vfc.cell(state));
            }
        }
    }

    @Test
    void testCorrelationComparisonPreservesVariableOrder() {
        String[] columns = {"contention", "avgServiceTime", "throughput"};
        CorrelationResult base = CorrelationResult.empty(columns);
        CorrelationResult cand = CorrelationResult.empty(columns);

        double[][] pearsonDeltas = new double[3][3];
        double[][] spearmanDeltas = new double[3][3];
        pearsonDeltas[0][1] = 0.15;
        spearmanDeltas[0][1] = 0.12;

        CorrelationComparison cc = new CorrelationComparison(base, cand, columns, pearsonDeltas, spearmanDeltas);
        assertSame(base, cc.baseline());
        assertSame(cand, cc.candidate());
        assertArrayEquals(columns, cc.columnNames());
        assertEquals(0.15, cc.pearsonDeltas()[0][1]);
        assertEquals(0.12, cc.spearmanDeltas()[0][1]);
    }

    @Test
    void testCoreComparison() {
        OccupancyComparison occ = new OccupancyComparison(
                BranchOccupancyResult.EMPTY, BranchOccupancyResult.EMPTY, null, null, 0, 0, 0, 0, 0, 0, 0, 0);
        TransitionComparison tc = new TransitionComparison(
                TransitionAnalysis.compute(new int[] {0, 1}),
                TransitionAnalysis.compute(new int[] {0, 1}),
                null,
                null,
                null,
                null,
                null,
                null);
        VectorField vf = VectorField.compute(new int[] {0, 1});
        VectorFieldComparison vfc = new VectorFieldComparison(vf, vf, null);

        CoreComparison core = new CoreComparison(
                3,
                Map.of(
                        "cycleStart.combined.throughput",
                        new ScalarComparison(
                                ScalarSummary.EMPTY,
                                ScalarSummary.EMPTY,
                                100.0,
                                100.0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0)),
                occ,
                occ,
                tc,
                tc,
                tc,
                tc,
                vfc,
                vfc,
                vfc,
                vfc,
                Map.of(),
                1.5,
                2.0,
                0.5);

        assertEquals(3, core.core());
        assertEquals(1, core.scalarComparisons().size());
        assertSame(occ, core.idleOccupancy());
        assertSame(occ, core.execOccupancy());
        assertSame(tc, core.idleHeadTransitions());
        assertSame(vfc, core.idleHeadVectorField());
        assertEquals(1.5, core.baselineCentroidDistance());
        assertEquals(2.0, core.candidateCentroidDistance());
        assertEquals(0.5, core.centroidDistanceDelta());
    }

    @Test
    void testAggregateComparison() {
        OccupancyComparison occ = new OccupancyComparison(
                BranchOccupancyResult.EMPTY, BranchOccupancyResult.EMPTY, null, null, 0, 0, 0, 0, 0, 0, 0, 0);
        AggregateComparison agg =
                new AggregateComparison(occ, occ, null, null, null, null, null, null, null, null, Map.of(), Map.of());

        assertSame(occ, agg.idleOccupancy());
        assertSame(occ, agg.execOccupancy());
        assertTrue(agg.scalarComparisons().isEmpty());
    }

    @Test
    void testCandidateComparisonPreservesBothIdentities() {
        RunIdentity baseId = new RunIdentity("t_base", "Baseline", null, 0, null, "/runs/base");
        RunIdentity candId = new RunIdentity("t_cand", "Candidate", null, 0, null, "/runs/cand");

        PerformanceComparison perf = new PerformanceComparison(
                ThroughputResult.of(100, 1, "ops/s"),
                ThroughputResult.of(120, 1, "ops/s"),
                20,
                20,
                ScalarSummary.EMPTY,
                ScalarSummary.EMPTY,
                ComparisonOutcome.B_BETTER);

        CandidateComparison comparison = new CandidateComparison(
                baseId, candId, ComparisonCompatibility.compatible(), List.of(), perf, List.of(), null);

        assertSame(baseId, comparison.baseline());
        assertSame(candId, comparison.candidate());
        assertEquals(CompatibilityStatus.COMPATIBLE, comparison.compatibility().status());
        assertSame(perf, comparison.performance());
    }

    @Test
    void testTopLevelComparisonResult() {
        RunIdentity baseId = new RunIdentity("base", "Baseline", null, 0, null, "/runs/base");
        RunIdentity cand1Id = new RunIdentity("cand1", "Candidate 1", null, 0, null, "/runs/cand1");
        RunIdentity cand2Id = new RunIdentity("cand2", "Candidate 2", null, 0, null, "/runs/cand2");

        TrialConfig config = new TrialConfig(1, 1, 1, null, "test-profile");
        CompletedRun baseRun = new CompletedRun(
                baseId, config, ThroughputResult.of(100, 1, "ops/s"), List.of(), RunArtifacts.standard("/runs/base"));
        CompletedRun cand1Run = new CompletedRun(
                cand1Id, config, ThroughputResult.of(110, 1, "ops/s"), List.of(), RunArtifacts.standard("/runs/cand1"));
        CompletedRun cand2Run = new CompletedRun(
                cand2Id, config, ThroughputResult.of(120, 1, "ops/s"), List.of(), RunArtifacts.standard("/runs/cand2"));

        PerformanceComparison perf1 = new PerformanceComparison(
                baseRun.throughput(),
                cand1Run.throughput(),
                10,
                10,
                ScalarSummary.EMPTY,
                ScalarSummary.EMPTY,
                ComparisonOutcome.B_BETTER);
        PerformanceComparison perf2 = new PerformanceComparison(
                baseRun.throughput(),
                cand2Run.throughput(),
                20,
                20,
                ScalarSummary.EMPTY,
                ScalarSummary.EMPTY,
                ComparisonOutcome.B_BETTER);

        CandidateComparison comp1 = new CandidateComparison(
                0, baseId, cand1Id, null, ComparisonCompatibility.compatible(), List.of(), perf1, List.of(), null);
        CandidateComparison comp2 = new CandidateComparison(
                1, baseId, cand2Id, null, ComparisonCompatibility.compatible(), List.of(), perf2, List.of(), null);

        // Top-level comparison represents strategy and pair comparisons
        ComparisonResult result =
                new ComparisonResult(calibration.config.ComparisonStrategy.BASELINE, List.of(comp1, comp2));
        assertEquals(calibration.config.ComparisonStrategy.BASELINE, result.strategy());
        assertEquals(2, result.comparisons().size());
        assertSame(comp1, result.comparisons().get(0));
        assertSame(comp2, result.comparisons().get(1));

        // Empty comparisons rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComparisonResult(calibration.config.ComparisonStrategy.BASELINE, List.of()));
    }
}
