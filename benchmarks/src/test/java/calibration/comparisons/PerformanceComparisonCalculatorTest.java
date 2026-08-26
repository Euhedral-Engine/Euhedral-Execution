package calibration.comparisons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.schema.ComparisonCompatibility;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.ConfigurationDifference;
import calibration.comparisons.schema.DifferenceCategory;
import calibration.comparisons.schema.PerformanceComparison;
import calibration.comparisons.schema.RunArtifacts;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ThroughputResult;
import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.TrialConfig;
import calibration.statistics.ComparisonOutcome;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.ScalarSummary;
import com.fasterxml.jackson.databind.node.TextNode;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerformanceComparisonCalculatorTest {

    private static TrialConfig baseTrialConfig() {
        CalibrationBenchmarkConfig calConfig = new CalibrationBenchmarkConfig(
                List.of(1, 2),
                4,
                2,
                10,
                false,
                1000L,
                5000L,
                FragmentDecisionWeights.DEFAULT,
                1024,
                true,
                true,
                true,
                true,
                true,
                true);
        return new TrialConfig(
                "trial_1",
                "Trial One",
                "group_a",
                "description",
                "hypothesis",
                null,
                List.of("tag1"),
                null,
                true,
                null,
                1,
                1,
                3,
                "2s",
                "5s",
                List.of("-Xms2g"),
                null,
                calConfig);
    }

    private static CompletedRun createRun(
            String id, List<Double> forkScores, double scoreError, String unit, SystemForkResult system) {
        TrialConfig config = baseTrialConfig();
        RunIdentity runIdentity = new RunIdentity(id, "Trial " + id, "grp", 0, null, "/path/to/" + id);
        double meanScore;
        if (!forkScores.isEmpty()) {
            double sum = 0.0;
            for (double s : forkScores) {
                sum += s;
            }
            meanScore = sum / forkScores.size();
        } else {
            meanScore = 100.0;
        }
        ThroughputResult tp = new ThroughputResult(meanScore, scoreError, unit, forkScores, List.of());
        RunArtifacts artifacts = RunArtifacts.standard("/path/to/" + id);
        return new CompletedRun(runIdentity, config, tp, system, List.of(), artifacts);
    }

    private static CompletedRun createRun(String id, List<Double> forkScores) {
        return createRun(id, forkScores, 1.0, "ops/s", SystemForkResult.EMPTY);
    }

    @Test
    void testCandidateWithHigherThroughputReturnsBBetter() {
        CompletedRun base = createRun("base", List.of(100.0, 100.0, 100.0, 100.0));
        CompletedRun cand = createRun("cand", List.of(120.0, 120.0, 120.0, 120.0));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertEquals(ComparisonOutcome.B_BETTER, perf.outcome());
        assertEquals(20.0, perf.absoluteDelta(), 1e-6);
        assertEquals(20.0, perf.relativeDeltaPercent(), 1e-6);
    }

    @Test
    void testBaselineWithHigherThroughputReturnsABetter() {
        CompletedRun base = createRun("base", List.of(120.0, 120.0, 120.0, 120.0));
        CompletedRun cand = createRun("cand", List.of(100.0, 100.0, 100.0, 100.0));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertEquals(ComparisonOutcome.A_BETTER, perf.outcome());
        assertEquals(-20.0, perf.absoluteDelta(), 1e-6);
        assertEquals(-16.666666666666668, perf.relativeDeltaPercent(), 1e-6);
    }

    @Test
    void testUncertaintyAdjustedWinnerInsidePracticalThresholdReturnsBetter() {
        CompletedRun base = createRun("base", List.of(100.0, 100.01, 99.99, 100.0));
        CompletedRun cand = createRun("cand", List.of(100.05, 100.06, 100.04, 100.05));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertEquals(ComparisonOutcome.B_BETTER, perf.outcome());
        assertEquals(0.05, perf.absoluteDelta(), 1e-6);
        assertEquals(0.05, perf.relativeDeltaPercent(), 1e-4);
    }

    @Test
    void testNoisyOverlappingDistributionsReturnInconclusive() {
        CompletedRun base = createRun("base", List.of(80.0, 120.0, 85.0, 115.0));
        CompletedRun cand = createRun("cand", List.of(82.0, 118.0, 88.0, 112.0));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertEquals(ComparisonOutcome.INCONCLUSIVE, perf.outcome());
    }

    @Test
    void testAbsoluteDeltaFormula() {
        CompletedRun base = createRun("base", List.of(200.0, 200.0));
        CompletedRun cand = createRun("cand", List.of(250.0, 250.0));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertEquals(50.0, perf.absoluteDelta(), 1e-9);
    }

    @Test
    void testRelativeDeltaPercentFormula() {
        CompletedRun base = createRun("base", List.of(200.0, 200.0));
        CompletedRun cand = createRun("cand", List.of(250.0, 250.0));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertEquals(25.0, perf.relativeDeltaPercent(), 1e-9);
    }

    @Test
    void testPositiveRelativeDeltaIndicatesImprovement() {
        CompletedRun base = createRun("base", List.of(100.0, 100.0));
        CompletedRun cand = createRun("cand", List.of(110.0, 110.0));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertTrue(perf.relativeDeltaPercent() > 0.0);
        assertTrue(perf.absoluteDelta() > 0.0);
    }

    @Test
    void testNegativeRelativeDeltaIndicatesRegression() {
        CompletedRun base = createRun("base", List.of(100.0, 100.0));
        CompletedRun cand = createRun("cand", List.of(90.0, 90.0));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertTrue(perf.relativeDeltaPercent() < 0.0);
        assertTrue(perf.absoluteDelta() < 0.0);
    }

    @Test
    void testIndependentForkDistributions() {
        List<Double> baseForks = List.of(100.0, 102.0, 98.0);
        List<Double> candForks = List.of(150.0, 155.0, 145.0, 160.0);

        CompletedRun base = createRun("base", baseForks);
        CompletedRun cand = createRun("cand", candForks);

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);

        ScalarSummary baseSummary = perf.baselineForkSummary();
        ScalarSummary candSummary = perf.candidateForkSummary();

        assertEquals(3L, baseSummary.count());
        assertEquals(100.0, baseSummary.mean(), 1e-6);
        assertEquals(4.0, baseSummary.variance(), 1e-6);

        assertEquals(4L, candSummary.count());
        assertEquals(152.5, candSummary.mean(), 1e-6);
        assertEquals(41.666666666666664, candSummary.variance(), 1e-6);
    }

    @Test
    void testReorderingForkSamplesDoesNotChangeResult() {
        List<Double> candForks1 = List.of(110.0, 120.0, 130.0, 140.0);
        List<Double> candForks2 = new ArrayList<>(candForks1);
        Collections.shuffle(candForks2);

        CompletedRun base = createRun("base", List.of(100.0, 100.0, 100.0));
        CompletedRun cand1 = createRun("cand1", candForks1);
        CompletedRun cand2 = createRun("cand2", candForks2);

        PerformanceComparison perf1 = PerformanceComparisonCalculator.compare(base, cand1);
        PerformanceComparison perf2 = PerformanceComparisonCalculator.compare(base, cand2);

        assertNotNull(perf1);
        assertNotNull(perf2);
        assertEquals(perf1.outcome(), perf2.outcome());
        assertEquals(perf1.absoluteDelta(), perf2.absoluteDelta(), 1e-9);
        assertEquals(perf1.relativeDeltaPercent(), perf2.relativeDeltaPercent(), 1e-9);
        assertEquals(
                perf1.candidateForkSummary().mean(),
                perf2.candidateForkSummary().mean(),
                1e-9);
        assertEquals(
                perf1.candidateForkSummary().variance(),
                perf2.candidateForkSummary().variance(),
                1e-9);
    }

    @Test
    void testNoPositionalForkPairingOccurs() {
        CompletedRun base = createRun("base", List.of(100.0, 102.0, 98.0));
        CompletedRun cand = createRun("cand", List.of(150.0, 152.0, 148.0, 151.0, 149.0));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertEquals(3L, perf.baselineForkSummary().count());
        assertEquals(5L, perf.candidateForkSummary().count());
        assertEquals(ComparisonOutcome.B_BETTER, perf.outcome());
    }

    @Test
    void testPartialCompatibilityPermitsThroughputComparison() {
        ConfigurationDifference diff = new ConfigurationDifference(
                "/calibrationConfig/observeIdleDecision",
                TextNode.valueOf("true"),
                TextNode.valueOf("false"),
                DifferenceCategory.OBSERVATION);
        ComparisonCompatibility partial =
                ComparisonCompatibility.partial(List.of(diff), List.of("Observation toggles differ"));

        CompletedRun base = createRun("base", List.of(100.0, 100.0));
        CompletedRun cand = createRun("cand", List.of(120.0, 120.0));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand, partial);
        assertNotNull(perf);
        assertEquals(ComparisonOutcome.B_BETTER, perf.outcome());
        assertEquals(20.0, perf.absoluteDelta(), 1e-6);
    }

    @Test
    void testIncompatibleCompatibilityBlocksComparison() {
        ConfigurationDifference diff = new ConfigurationDifference(
                "/calibrationConfig/workUnits",
                TextNode.valueOf("10"),
                TextNode.valueOf("100"),
                DifferenceCategory.WORKLOAD);
        ComparisonCompatibility incompatible =
                ComparisonCompatibility.incompatible(List.of(diff), List.of("Work units mismatch"));

        CompletedRun base = createRun("base", List.of(100.0, 100.0));
        CompletedRun cand = createRun("cand", List.of(120.0, 120.0));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand, incompatible);
        assertNull(perf);
    }

    @Test
    void testThroughputUnitMismatchIsRejectedDefensively() {
        CompletedRun base = createRun("base", List.of(100.0, 100.0), 1.0, "ops/s", SystemForkResult.EMPTY);
        CompletedRun cand = createRun("cand", List.of(120.0, 120.0), 1.0, "ops/ms", SystemForkResult.EMPTY);

        assertThrows(
                IllegalArgumentException.class,
                () -> PerformanceComparisonCalculator.compare(base, cand, ComparisonCompatibility.compatible()));
    }

    @Test
    void testSingleForkInputDoesNotFabricateVarianceOrEquivalence() {
        CompletedRun base = createRun("base", List.of(100.0));
        CompletedRun cand = createRun("cand", List.of(100.05));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertEquals(ComparisonOutcome.INCONCLUSIVE, perf.outcome());
        assertEquals(1L, perf.baselineForkSummary().count());
        assertEquals(1L, perf.candidateForkSummary().count());
        assertTrue(Double.isNaN(perf.baselineForkSummary().variance()));
        assertTrue(Double.isNaN(perf.candidateForkSummary().variance()));
        assertEquals(0.05, perf.absoluteDelta(), 1e-6);
    }

    @Test
    void testRawForkScoresPreservedInThroughputResult() {
        List<Double> baseForks = List.of(99.0, 101.0, 100.0);
        List<Double> candForks = List.of(119.0, 121.0, 120.0);

        CompletedRun base = createRun("base", baseForks);
        CompletedRun cand = createRun("cand", candForks);

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertSame(base.throughput(), perf.baseline());
        assertSame(cand.throughput(), perf.candidate());
        assertEquals(baseForks, perf.baseline().forkScores());
        assertEquals(candForks, perf.candidate().forkScores());
    }

    @Test
    void testObserverTelemetryDoesNotAffectPerformanceVerdict() {
        CompletedRun base = createRun("base", List.of(100.0, 100.0, 100.0), 1.0, "ops/s", SystemForkResult.EMPTY);
        CompletedRun cand = createRun("cand", List.of(120.0, 120.0, 120.0), 1.0, "ops/s", SystemForkResult.EMPTY);

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertEquals(ComparisonOutcome.B_BETTER, perf.outcome());
    }

    @Test
    void testSingleScoreFallbackWhenForkScoresEmpty() {
        TrialConfig config = baseTrialConfig();
        RunIdentity baseId = new RunIdentity("base", "Base", "grp", 0, null, "/base");
        RunIdentity candId = new RunIdentity("cand", "Cand", "grp", 0, null, "/cand");

        ThroughputResult baseTp = ThroughputResult.of(100.0, 1.0, "ops/s");
        ThroughputResult candTp = ThroughputResult.of(120.0, 1.0, "ops/s");

        CompletedRun base = new CompletedRun(
                baseId, config, baseTp, SystemForkResult.EMPTY, List.of(), RunArtifacts.standard("/base"));
        CompletedRun cand = new CompletedRun(
                candId, config, candTp, SystemForkResult.EMPTY, List.of(), RunArtifacts.standard("/cand"));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertEquals(100.0, perf.baselineForkSummary().mean(), 1e-6);
        assertEquals(120.0, perf.candidateForkSummary().mean(), 1e-6);
        assertEquals(20.0, perf.absoluteDelta(), 1e-6);
        assertEquals(ComparisonOutcome.INCONCLUSIVE, perf.outcome());
    }

    @Test
    void testMultipleCandidatesEvaluatedIndependently() {
        CompletedRun base = createRun("base", List.of(100.0, 100.0, 100.0));
        CompletedRun candA = createRun("candA", List.of(120.0, 120.0, 120.0));
        CompletedRun candB = createRun("candB", List.of(80.0, 80.0, 80.0));
        CompletedRun candC = createRun("candC", List.of(100.01, 100.02, 99.99));

        PerformanceComparison compA = PerformanceComparisonCalculator.compare(base, candA);
        PerformanceComparison compB = PerformanceComparisonCalculator.compare(base, candB);
        PerformanceComparison compC = PerformanceComparisonCalculator.compare(base, candC);

        assertNotNull(compA);
        assertNotNull(compB);
        assertNotNull(compC);

        assertEquals(ComparisonOutcome.B_BETTER, compA.outcome());
        assertEquals(ComparisonOutcome.A_BETTER, compB.outcome());
        assertEquals(ComparisonOutcome.EQUIVALENT, compC.outcome());
    }

    @Test
    void testNullAndInvalidInputs() {
        CompletedRun valid = createRun("valid", List.of(100.0, 100.0));

        assertThrows(NullPointerException.class, () -> PerformanceComparisonCalculator.compare(null, valid));
        assertThrows(NullPointerException.class, () -> PerformanceComparisonCalculator.compare(valid, null));
        assertThrows(NullPointerException.class, () -> PerformanceComparisonCalculator.compare(valid, valid, null));

        CompletedRun nonPositiveBase = createRun("neg", List.of(-10.0, -20.0));
        assertThrows(
                IllegalArgumentException.class, () -> PerformanceComparisonCalculator.compare(nonPositiveBase, valid));

        CompletedRun nanCand = createRun("nan", List.of(Double.NaN, 100.0));
        assertThrows(IllegalArgumentException.class, () -> PerformanceComparisonCalculator.compare(valid, nanCand));
    }
}
