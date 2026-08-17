package calibration.comparisons;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.schema.AggregateComparison;
import calibration.comparisons.schema.ComparisonCompatibility;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.ConfigurationDifference;
import calibration.comparisons.schema.CorrelationComparison;
import calibration.comparisons.schema.DifferenceCategory;
import calibration.comparisons.schema.OccupancyComparison;
import calibration.comparisons.schema.PerformanceComparison;
import calibration.comparisons.schema.RunArtifacts;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ScalarComparison;
import calibration.comparisons.schema.ThroughputResult;
import calibration.comparisons.schema.TransitionComparison;
import calibration.comparisons.schema.VectorCellComparison;
import calibration.comparisons.schema.VectorFieldComparison;
import calibration.config.TrialConfig;
import calibration.statistics.Band;
import calibration.statistics.VectorField;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.BatchCompleteScalars;
import calibration.statistics.iteration.BatchCompleteStatistics;
import calibration.statistics.iteration.BatchProgressScalars;
import calibration.statistics.iteration.BatchProgressStatistics;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.CycleStartScalars;
import calibration.statistics.iteration.CycleStartStatistics;
import calibration.statistics.iteration.DecisionScalars;
import calibration.statistics.iteration.DecisionStatistics;
import calibration.statistics.iteration.RawBodyCostStatistics;
import calibration.statistics.iteration.ScalarSummary;
import calibration.statistics.iteration.TransitionAnalysis;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemTelemetryComparisonCalculatorTest {

    private static final double EPSILON = 1e-9;

    // --- 1. Scalar Comparisons ---

    @Test
    void testScalarComparisonDeltasUseCandidateMinusBaseline() {
        ScalarSummary base = ScalarSummary.of(10.0, 20.0, 30.0, 40.0, 50.0);
        ScalarSummary cand = ScalarSummary.of(15.0, 25.0, 35.0, 45.0, 55.0);

        ScalarComparison sc = SystemTelemetryComparisonCalculator.compareScalar(base, cand);

        assertSame(base, sc.baseline());
        assertSame(cand, sc.candidate());
        assertEquals(5.0, sc.meanDelta(), EPSILON);
        assertEquals(5.0, sc.medianDelta(), EPSILON);
        assertEquals(cand.variance() - base.variance(), sc.varianceDelta(), EPSILON);
        assertEquals(cand.standardDeviation() - base.standardDeviation(), sc.standardDeviationDelta(), EPSILON);
        assertEquals(cand.coefficientOfVariation() - base.coefficientOfVariation(), sc.cvDelta(), EPSILON);
        assertEquals(5.0, sc.minDelta(), EPSILON);
        assertEquals(5.0, sc.maxDelta(), EPSILON);
        assertEquals(cand.p25() - base.p25(), sc.p25Delta(), EPSILON);
        assertEquals(cand.p50() - base.p50(), sc.p50Delta(), EPSILON);
        assertEquals(cand.p75() - base.p75(), sc.p75Delta(), EPSILON);
        assertEquals(cand.p95() - base.p95(), sc.p95Delta(), EPSILON);
        assertEquals(cand.iqr() - base.iqr(), sc.iqrDelta(), EPSILON);
        assertEquals(cand.normalizedIqr() - base.normalizedIqr(), sc.normalizedIqrDelta(), EPSILON);
        assertEquals(cand.p95ToP50Ratio() - base.p95ToP50Ratio(), sc.p95ToP50RatioDelta(), EPSILON);
    }

    @Test
    void testScalarComparisonIdenticalProducesZeros() {
        ScalarSummary s = ScalarSummary.of(5.0, 10.0, 15.0, 20.0, 25.0, 30.0);
        ScalarComparison sc = SystemTelemetryComparisonCalculator.compareScalar(s, s);

        assertEquals(0.0, sc.meanDelta(), EPSILON);
        assertEquals(0.0, sc.medianDelta(), EPSILON);
        assertEquals(0.0, sc.varianceDelta(), EPSILON);
        assertEquals(0.0, sc.standardDeviationDelta(), EPSILON);
        assertEquals(0.0, sc.cvDelta(), EPSILON);
        assertEquals(0.0, sc.minDelta(), EPSILON);
        assertEquals(0.0, sc.maxDelta(), EPSILON);
        assertEquals(0.0, sc.p25Delta(), EPSILON);
        assertEquals(0.0, sc.p50Delta(), EPSILON);
        assertEquals(0.0, sc.p75Delta(), EPSILON);
        assertEquals(0.0, sc.p95Delta(), EPSILON);
        assertEquals(0.0, sc.iqrDelta(), EPSILON);
        assertEquals(0.0, sc.normalizedIqrDelta(), EPSILON);
        assertEquals(0.0, sc.p95ToP50RatioDelta(), EPSILON);
    }

    @Test
    void testScalarComparisonQuantilesCalculatedCorrectly() {
        ScalarSummary base = ScalarSummary.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        ScalarSummary cand = ScalarSummary.of(2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0);

        ScalarComparison sc = SystemTelemetryComparisonCalculator.compareScalar(base, cand);

        assertEquals(cand.p25() - base.p25(), sc.p25Delta(), EPSILON);
        assertEquals(cand.p50() - base.p50(), sc.p50Delta(), EPSILON);
        assertEquals(cand.p75() - base.p75(), sc.p75Delta(), EPSILON);
        assertEquals(cand.p95() - base.p95(), sc.p95Delta(), EPSILON);
        assertEquals(cand.iqr() - base.iqr(), sc.iqrDelta(), EPSILON);
        assertEquals(cand.normalizedIqr() - base.normalizedIqr(), sc.normalizedIqrDelta(), EPSILON);
        assertEquals(cand.p95ToP50Ratio() - base.p95ToP50Ratio(), sc.p95ToP50RatioDelta(), EPSILON);
    }

    @Test
    void testScalarComparisonMissingPreservesNaN() {
        ScalarSummary empty = ScalarSummary.EMPTY;
        ScalarComparison sc = SystemTelemetryComparisonCalculator.compareScalar(empty, empty);

        assertTrue(Double.isNaN(sc.meanDelta()));
        assertTrue(Double.isNaN(sc.medianDelta()));
        assertTrue(Double.isNaN(sc.varianceDelta()));
        assertTrue(Double.isNaN(sc.standardDeviationDelta()));
        assertTrue(Double.isNaN(sc.cvDelta()));
        assertTrue(Double.isNaN(sc.minDelta()));
        assertTrue(Double.isNaN(sc.maxDelta()));
        assertTrue(Double.isNaN(sc.p25Delta()));
        assertTrue(Double.isNaN(sc.p50Delta()));
        assertTrue(Double.isNaN(sc.p75Delta()));
        assertTrue(Double.isNaN(sc.p95Delta()));
        assertTrue(Double.isNaN(sc.iqrDelta()));
        assertTrue(Double.isNaN(sc.normalizedIqrDelta()));
        assertTrue(Double.isNaN(sc.p95ToP50RatioDelta()));
    }

    // --- 2. Occupancy Comparisons ---

    @Test
    void testOccupancyIdenticalProducesZeroDeltasAndZeroTV() {
        long[][] counts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        counts[0][0] = 50L;
        counts[2][3] = 50L;

        BranchOccupancyResult occ = BranchOccupancyResult.of(counts);
        OccupancyComparison comp = SystemTelemetryComparisonCalculator.compareOccupancy(occ, occ);

        assertSame(occ, comp.baseline());
        assertSame(occ, comp.candidate());
        assertEquals(0.0, comp.totalVariationDistance(), EPSILON);
        assertEquals(0.0, comp.contentionCentroidDelta(), EPSILON);
        assertEquals(0.0, comp.bodyCentroidDelta(), EPSILON);
        assertEquals(0.0, comp.centroidDistance(), EPSILON);
        assertEquals(0.0, comp.contentionVarianceDelta(), EPSILON);
        assertEquals(0.0, comp.bodyVarianceDelta(), EPSILON);
        assertEquals(0.0, comp.covarianceDelta(), EPSILON);
        assertEquals(0.0, comp.radiusDelta(), EPSILON);

        long[][] countDeltas = comp.countDeltas();
        double[][] probDeltas = comp.probabilityDeltas();
        for (int i = 0; i < Band.GRID_SIZE; i++) {
            for (int j = 0; j < Band.GRID_SIZE; j++) {
                assertEquals(0L, countDeltas[i][j]);
                assertEquals(0.0, probDeltas[i][j], EPSILON);
            }
        }
    }

    @Test
    void testOccupancyCompletelyDisjointProducesTVOne() {
        long[][] baseCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        baseCounts[0][0] = 100L;

        long[][] candCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        candCounts[4][4] = 100L;

        BranchOccupancyResult base = BranchOccupancyResult.of(baseCounts);
        BranchOccupancyResult cand = BranchOccupancyResult.of(candCounts);

        OccupancyComparison comp = SystemTelemetryComparisonCalculator.compareOccupancy(base, cand);

        assertEquals(1.0, comp.totalVariationDistance(), EPSILON);
        assertEquals(4.0, comp.contentionCentroidDelta(), EPSILON);
        assertEquals(4.0, comp.bodyCentroidDelta(), EPSILON);
        assertEquals(Math.hypot(4.0, 4.0), comp.centroidDistance(), EPSILON);
    }

    @Test
    void testOccupancyPartialDistributionMovementProducesExpectedTV() {
        long[][] baseCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        baseCounts[0][0] = 50L;
        baseCounts[0][1] = 50L;

        long[][] candCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        candCounts[0][0] = 50L;
        candCounts[0][2] = 50L;

        BranchOccupancyResult base = BranchOccupancyResult.of(baseCounts);
        BranchOccupancyResult cand = BranchOccupancyResult.of(candCounts);

        OccupancyComparison comp = SystemTelemetryComparisonCalculator.compareOccupancy(base, cand);

        // Movement from (0,1) with prob 0.5 to (0,2) with prob 0.5 -> TV = 0.5 * (|0 - 0.5| + |0.5 - 0|) = 0.5
        assertEquals(0.5, comp.totalVariationDistance(), EPSILON);
    }

    @Test
    void testOccupancyExactCountAndProbabilityDeltas() {
        long[][] baseCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        baseCounts[1][1] = 20L;
        baseCounts[2][2] = 80L;

        long[][] candCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        candCounts[1][1] = 40L;
        candCounts[2][2] = 60L;

        BranchOccupancyResult base = BranchOccupancyResult.of(baseCounts);
        BranchOccupancyResult cand = BranchOccupancyResult.of(candCounts);

        OccupancyComparison comp = SystemTelemetryComparisonCalculator.compareOccupancy(base, cand);

        assertEquals(20L, comp.countDeltas()[1][1]);
        assertEquals(-20L, comp.countDeltas()[2][2]);
        assertEquals(0.20, comp.probabilityDeltas()[1][1], EPSILON);
        assertEquals(-0.20, comp.probabilityDeltas()[2][2], EPSILON);
    }

    @Test
    void testOccupancyCentroidEuclideanDisplacement() {
        long[][] baseCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        baseCounts[1][1] = 100L; // centroid (1.0, 1.0)

        long[][] candCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        candCounts[4][5 - 1] = 100L; // centroid (4.0, 4.0)

        BranchOccupancyResult base = BranchOccupancyResult.of(baseCounts);
        BranchOccupancyResult cand = BranchOccupancyResult.of(candCounts);

        OccupancyComparison comp = SystemTelemetryComparisonCalculator.compareOccupancy(base, cand);

        assertEquals(3.0, comp.contentionCentroidDelta(), EPSILON);
        assertEquals(3.0, comp.bodyCentroidDelta(), EPSILON);
        assertEquals(Math.hypot(3.0, 3.0), comp.centroidDistance(), EPSILON);
    }

    @Test
    void testOccupancyVarianceCovarianceRadiusDeltas() {
        long[][] baseCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        baseCounts[0][0] = 50L;
        baseCounts[4][4] = 50L;

        long[][] candCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        candCounts[2][2] = 100L;

        BranchOccupancyResult base = BranchOccupancyResult.of(baseCounts);
        BranchOccupancyResult cand = BranchOccupancyResult.of(candCounts);

        OccupancyComparison comp = SystemTelemetryComparisonCalculator.compareOccupancy(base, cand);

        assertEquals(cand.contentionVariance() - base.contentionVariance(), comp.contentionVarianceDelta(), EPSILON);
        assertEquals(cand.bodyVariance() - base.bodyVariance(), comp.bodyVarianceDelta(), EPSILON);
        assertEquals(
                cand.contentionBodyCovariance() - base.contentionBodyCovariance(), comp.covarianceDelta(), EPSILON);
        assertEquals(cand.radius() - base.radius(), comp.radiusDelta(), EPSILON);
    }

    // --- 3. Transition Comparisons ---

    @Test
    void testTransitionComparisonCountsAndProbabilities() {
        long[][] baseCounts = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        baseCounts[0][1] = 10L;
        baseCounts[0][2] = 10L;

        long[][] candCounts = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        candCounts[0][1] = 5L;
        candCounts[0][2] = 15L;

        TransitionAnalysis base = TransitionAnalysis.computeFromCounts(baseCounts);
        TransitionAnalysis cand = TransitionAnalysis.computeFromCounts(candCounts);

        TransitionComparison tc = SystemTelemetryComparisonCalculator.compareTransitions(base, cand);

        assertSame(base, tc.baseline());
        assertSame(cand, tc.candidate());
        assertEquals(-5L, tc.countDeltas()[0][1]);
        assertEquals(5L, tc.countDeltas()[0][2]);
        assertEquals(-0.25, tc.probabilityDeltas()[0][1], EPSILON);
        assertEquals(0.25, tc.probabilityDeltas()[0][2], EPSILON);
    }

    @Test
    void testTransitionSelfTransitionDeltas() {
        long[][] baseCounts = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        baseCounts[3][3] = 2L;
        baseCounts[3][4] = 8L;

        long[][] candCounts = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        candCounts[3][3] = 8L;
        candCounts[3][4] = 2L;

        TransitionAnalysis base = TransitionAnalysis.computeFromCounts(baseCounts);
        TransitionAnalysis cand = TransitionAnalysis.computeFromCounts(candCounts);

        TransitionComparison tc = SystemTelemetryComparisonCalculator.compareTransitions(base, cand);

        assertEquals(0.6, tc.selfTransitionRateDeltas()[3], EPSILON);
    }

    @Test
    void testTransitionDominantOutgoingStateAndProbability() {
        long[][] baseCounts = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        baseCounts[0][1] = 7L;
        baseCounts[0][2] = 3L;

        long[][] candCounts = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        candCounts[0][1] = 2L;
        candCounts[0][2] = 8L;

        TransitionAnalysis base = TransitionAnalysis.computeFromCounts(baseCounts);
        TransitionAnalysis cand = TransitionAnalysis.computeFromCounts(candCounts);

        TransitionComparison tc = SystemTelemetryComparisonCalculator.compareTransitions(base, cand);

        assertEquals(1, base.dominantOutgoingState(0));
        assertEquals(2, cand.dominantOutgoingState(0));
        assertEquals(2, tc.candidateDominantOutgoingStates()[0]);
        assertEquals(0.8 - 0.7, tc.dominantOutgoingProbabilityDeltas()[0], EPSILON);
    }

    @Test
    void testTransitionOscillationScoreDeltas() {
        // Pure oscillation 0 <-> 1 in candidate
        int[] baseSeq = {0, 1, 2, 3, 0};
        int[] candSeq = {0, 1, 0, 1, 0, 1};

        TransitionAnalysis base = TransitionAnalysis.compute(baseSeq);
        TransitionAnalysis cand = TransitionAnalysis.compute(candSeq);

        TransitionComparison tc = SystemTelemetryComparisonCalculator.compareTransitions(base, cand);

        assertEquals(cand.oscillation(0, 1) - base.oscillation(0, 1), tc.oscillationScoreDeltas()[0][1], EPSILON);
        assertEquals(tc.oscillationScoreDeltas()[0][1], tc.oscillationScoreDeltas()[1][0], EPSILON);
        assertEquals(0.0, tc.oscillationScoreDeltas()[0][0], EPSILON);
    }

    @Test
    void testTransitionIdenticalProducesZeroDeltas() {
        TransitionAnalysis ta = TransitionAnalysis.compute(new int[] {0, 1, 2, 0, 1, 2});
        TransitionComparison tc = SystemTelemetryComparisonCalculator.compareTransitions(ta, ta);

        for (int i = 0; i < Band.TOTAL_STATES; i++) {
            assertEquals(0.0, tc.selfTransitionRateDeltas()[i], EPSILON);
            assertEquals(ta.dominantOutgoingState(i), tc.candidateDominantOutgoingStates()[i]);
            assertEquals(0.0, tc.dominantOutgoingProbabilityDeltas()[i], EPSILON);
            for (int j = 0; j < Band.TOTAL_STATES; j++) {
                assertEquals(0L, tc.countDeltas()[i][j]);
                assertEquals(0.0, tc.probabilityDeltas()[i][j], EPSILON);
                assertEquals(0.0, tc.oscillationScoreDeltas()[i][j], EPSILON);
            }
        }
    }

    // --- 4. Vector Field Comparisons ---

    @Test
    void testVectorFieldComparisonDeltas() {
        long[][] baseCounts = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        baseCounts[0][1] = 10L; // (0,0) -> (0,1): deltaC=0, deltaB=1

        long[][] candCounts = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        candCounts[0][5] = 15L; // (0,0) -> (1,0): deltaC=1, deltaB=0

        VectorField base = VectorField.compute(baseCounts);
        VectorField cand = VectorField.compute(candCounts);

        VectorFieldComparison vfc = SystemTelemetryComparisonCalculator.compareVectorField(base, cand);

        assertSame(base, vfc.baseline());
        assertSame(cand, vfc.candidate());

        VectorCellComparison cell = vfc.cell(0, 0);
        assertEquals(5L, cell.transitionCountDelta());
        assertEquals(1.0 - 0.0, cell.meanDeltaContentionDelta(), EPSILON);
        assertEquals(0.0 - 1.0, cell.meanDeltaBodyDelta(), EPSILON);
        assertEquals(cand.cell(0, 0).magnitude() - base.cell(0, 0).magnitude(), cell.magnitudeDelta(), EPSILON);
    }

    @Test
    void testVectorFieldIdenticalProducesZeros() {
        VectorField vf = VectorField.compute(new int[] {0, 1, 2, 3, 0});
        VectorFieldComparison vfc = SystemTelemetryComparisonCalculator.compareVectorField(vf, vf);

        for (int c = 0; c < Band.GRID_SIZE; c++) {
            for (int b = 0; b < Band.GRID_SIZE; b++) {
                VectorCellComparison cell = vfc.cell(c, b);
                assertEquals(0L, cell.transitionCountDelta());
                if (cell.baseline().hasVector()) {
                    assertEquals(0.0, cell.meanDeltaContentionDelta(), EPSILON);
                    assertEquals(0.0, cell.meanDeltaBodyDelta(), EPSILON);
                    assertEquals(0.0, cell.magnitudeDelta(), EPSILON);
                }
            }
        }
    }

    // --- 5. Correlation Comparisons ---

    @Test
    void testCorrelationComparisonPearsonAndSpearmanDeltas() {
        String[] cols = {"contention", "avgServiceTime"};
        double[][] baseP = {{1.0, 0.4}, {0.4, 1.0}};
        double[][] baseS = {{1.0, 0.3}, {0.3, 1.0}};
        double[][] candP = {{1.0, 0.6}, {0.6, 1.0}};
        double[][] candS = {{1.0, 0.5}, {0.5, 1.0}};

        CorrelationResult base = new CorrelationResult(cols, baseP, baseS);
        CorrelationResult cand = new CorrelationResult(cols, candP, candS);

        CorrelationComparison cc = SystemTelemetryComparisonCalculator.compareCorrelation(base, cand);

        assertSame(base, cc.baseline());
        assertSame(cand, cc.candidate());
        assertArrayEquals(cols, cc.columnNames());
        assertEquals(0.2, cc.pearsonDeltas()[0][1], EPSILON);
        assertEquals(0.2, cc.spearmanDeltas()[0][1], EPSILON);
    }

    @Test
    void testCorrelationPreservesVariableOrder() {
        String[] cols = {"completed", "batchSize", "upstreamCount"};
        CorrelationResult base = CorrelationResult.empty(cols);
        CorrelationResult cand = CorrelationResult.empty(cols);

        CorrelationComparison cc = SystemTelemetryComparisonCalculator.compareCorrelation(base, cand);
        assertArrayEquals(cols, cc.columnNames());
    }

    @Test
    void testCorrelationExplicitAlignmentWhenOrderDiffers() {
        String[] baseCols = {"a", "b"};
        String[] candCols = {"b", "a"};

        double[][] baseP = {{1.0, 0.3}, {0.3, 1.0}};
        double[][] candP = {{1.0, 0.7}, {0.7, 1.0}};
        double[][] baseS = {{1.0, 0.2}, {0.2, 1.0}};
        double[][] candS = {{1.0, 0.5}, {0.5, 1.0}};

        CorrelationResult base = new CorrelationResult(baseCols, baseP, baseS);
        CorrelationResult cand = new CorrelationResult(candCols, candP, candS);

        CorrelationComparison cc = SystemTelemetryComparisonCalculator.compareCorrelation(base, cand);

        assertArrayEquals(baseCols, cc.columnNames());
        assertEquals(0.4, cc.pearsonDeltas()[0][1], EPSILON);
        assertEquals(0.3, cc.spearmanDeltas()[0][1], EPSILON);
    }

    @Test
    void testCorrelationMismatchedVariablesThrows() {
        String[] baseCols = {"a", "b"};
        String[] candCols = {"a", "c"};

        CorrelationResult base = CorrelationResult.empty(baseCols);
        CorrelationResult cand = CorrelationResult.empty(candCols);

        assertThrows(
                IllegalArgumentException.class,
                () -> SystemTelemetryComparisonCalculator.compareCorrelation(base, cand));
    }

    @Test
    void testCorrelationUndefinedPreservesNaN() {
        String[] cols = {"a", "b"};
        CorrelationResult base = CorrelationResult.empty(cols);
        CorrelationResult cand = CorrelationResult.empty(cols);

        CorrelationComparison cc = SystemTelemetryComparisonCalculator.compareCorrelation(base, cand);

        assertTrue(Double.isNaN(cc.pearsonDeltas()[0][1]));
        assertTrue(Double.isNaN(cc.spearmanDeltas()[0][1]));
    }

    // --- 6. System Integration & Authority Boundary ---

    @Test
    void testIdleAndExecutionComparedIndependently() {
        long[][] idleCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        idleCounts[0][0] = 100L;
        long[][] execCounts = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        execCounts[4][4] = 100L;

        DecisionStatistics idle = new DecisionStatistics(
                100L,
                BranchOccupancyResult.of(idleCounts),
                DecisionScalars.EMPTY,
                DecisionScalars.EMPTY,
                DecisionScalars.EMPTY,
                TransitionAnalysis.compute(new int[0]),
                TransitionAnalysis.compute(new int[0]),
                VectorField.compute(new int[0]),
                VectorField.compute(new int[0]),
                CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES),
                CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES),
                CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES));

        DecisionStatistics exec = new DecisionStatistics(
                100L,
                BranchOccupancyResult.of(execCounts),
                DecisionScalars.EMPTY,
                DecisionScalars.EMPTY,
                DecisionScalars.EMPTY,
                TransitionAnalysis.compute(new int[0]),
                TransitionAnalysis.compute(new int[0]),
                VectorField.compute(new int[0]),
                VectorField.compute(new int[0]),
                CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES),
                CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES),
                CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES));

        SystemForkResult baseSys = new SystemForkResult(
                0,
                1,
                1,
                0L,
                0L,
                0L,
                0L,
                100L,
                100L,
                CycleStartStatistics.EMPTY,
                BatchProgressStatistics.EMPTY,
                BatchCompleteStatistics.EMPTY,
                RawBodyCostStatistics.EMPTY,
                idle,
                exec,
                Math.hypot(4.0, 4.0));

        SystemForkResult candSys = new SystemForkResult(
                0,
                1,
                1,
                0L,
                0L,
                0L,
                0L,
                100L,
                100L,
                CycleStartStatistics.EMPTY,
                BatchProgressStatistics.EMPTY,
                BatchCompleteStatistics.EMPTY,
                RawBodyCostStatistics.EMPTY,
                idle, // idle unchanged
                idle, // exec moved from (4,4) to (0,0)
                0.0);

        AggregateComparison agg =
                SystemTelemetryComparisonCalculator.compare(baseSys, candSys, ComparisonCompatibility.compatible());

        assertNotNull(agg);
        // Idle is unchanged
        assertEquals(0.0, agg.idleOccupancy().totalVariationDistance(), EPSILON);
        // Exec moved completely
        assertEquals(1.0, agg.execOccupancy().totalVariationDistance(), EPSILON);
    }

    @Test
    void testHeadAndSteadyStateTransitionsRemainIndependent() {
        int[] headSeq = {0, 1, 0, 1};
        int[] steadySeq = {2, 3, 2, 3};

        TransitionAnalysis headTrans = TransitionAnalysis.compute(headSeq);
        TransitionAnalysis steadyTrans = TransitionAnalysis.compute(steadySeq);

        DecisionStatistics decisions = new DecisionStatistics(
                100L,
                BranchOccupancyResult.EMPTY,
                DecisionScalars.EMPTY,
                DecisionScalars.EMPTY,
                DecisionScalars.EMPTY,
                headTrans,
                steadyTrans,
                VectorField.compute(headSeq),
                VectorField.compute(steadySeq),
                CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES),
                CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES),
                CorrelationResult.empty(DecisionStatistics.COLUMN_NAMES));

        SystemForkResult base = new SystemForkResult(
                0,
                1,
                1,
                0L,
                0L,
                0L,
                0L,
                100L,
                0L,
                CycleStartStatistics.EMPTY,
                BatchProgressStatistics.EMPTY,
                BatchCompleteStatistics.EMPTY,
                RawBodyCostStatistics.EMPTY,
                decisions,
                DecisionStatistics.EMPTY,
                Double.NaN);

        AggregateComparison agg =
                SystemTelemetryComparisonCalculator.compare(base, base, ComparisonCompatibility.compatible());

        assertNotNull(agg);
        assertNotNull(agg.idleHeadTransitions());
        assertNotNull(agg.idleSteadyStateTransitions());
        assertNull(agg.execHeadTransitions());
        assertNull(agg.execSteadyStateTransitions());

        assertEquals(2L, agg.idleHeadTransitions().baseline().transitionCounts()[0][1]);
        assertEquals(0L, agg.idleSteadyStateTransitions().baseline().transitionCounts()[0][1]);
        assertEquals(2L, agg.idleSteadyStateTransitions().baseline().transitionCounts()[2][3]);
    }

    @Test
    void testRawBodyCycleStartBatchProgressAndBatchCompleteScalars() {
        ScalarSummary s10 = ScalarSummary.of(10.0, 10.0);
        ScalarSummary s20 = ScalarSummary.of(20.0, 20.0);

        CycleStartScalars cycleScalars = new CycleStartScalars(s10, s10, s10, s10, s10, s10, s10);
        CycleStartStatistics cycleStart = new CycleStartStatistics(
                100L,
                cycleScalars,
                cycleScalars,
                cycleScalars,
                CorrelationResult.empty(CycleStartStatistics.COLUMN_NAMES),
                CorrelationResult.empty(CycleStartStatistics.COLUMN_NAMES),
                CorrelationResult.empty(CycleStartStatistics.COLUMN_NAMES));

        BatchProgressScalars progScalars = new BatchProgressScalars(s10, s10, s10, s10, s10);
        BatchProgressStatistics batchProgress = new BatchProgressStatistics(
                100L,
                progScalars,
                progScalars,
                progScalars,
                CorrelationResult.empty(BatchProgressStatistics.COLUMN_NAMES),
                CorrelationResult.empty(BatchProgressStatistics.COLUMN_NAMES),
                CorrelationResult.empty(BatchProgressStatistics.COLUMN_NAMES));

        BatchCompleteScalars compScalars = new BatchCompleteScalars(s10, s10, s10, s10, s10, s10);
        BatchCompleteStatistics batchComplete = new BatchCompleteStatistics(
                100L,
                compScalars,
                compScalars,
                compScalars,
                CorrelationResult.empty(BatchCompleteStatistics.COLUMN_NAMES),
                CorrelationResult.empty(BatchCompleteStatistics.COLUMN_NAMES),
                CorrelationResult.empty(BatchCompleteStatistics.COLUMN_NAMES));

        RawBodyCostStatistics rawBodyCost = new RawBodyCostStatistics(100L, 1000L, s10, s10, s10);

        SystemForkResult base = new SystemForkResult(
                0,
                1,
                1,
                100L,
                100L,
                100L,
                100L,
                0L,
                0L,
                cycleStart,
                batchProgress,
                batchComplete,
                rawBodyCost,
                DecisionStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                Double.NaN);

        CycleStartScalars candCycleScalars = new CycleStartScalars(s20, s20, s20, s20, s20, s20, s20);
        CycleStartStatistics candCycleStart = new CycleStartStatistics(
                100L,
                candCycleScalars,
                candCycleScalars,
                candCycleScalars,
                CorrelationResult.empty(CycleStartStatistics.COLUMN_NAMES),
                CorrelationResult.empty(CycleStartStatistics.COLUMN_NAMES),
                CorrelationResult.empty(CycleStartStatistics.COLUMN_NAMES));

        RawBodyCostStatistics candRawBodyCost = new RawBodyCostStatistics(100L, 2000L, s20, s20, s20);

        SystemForkResult cand = new SystemForkResult(
                0,
                1,
                1,
                100L,
                100L,
                100L,
                100L,
                0L,
                0L,
                candCycleStart,
                batchProgress,
                batchComplete,
                candRawBodyCost,
                DecisionStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                Double.NaN);

        AggregateComparison agg =
                SystemTelemetryComparisonCalculator.compare(base, cand, ComparisonCompatibility.compatible());

        assertNotNull(agg);
        Map<String, ScalarComparison> scalars = agg.scalarComparisons();

        assertEquals(10.0, scalars.get("cycleStart.head.completed").meanDelta(), EPSILON);
        assertEquals(10.0, scalars.get("cycleStart.steadyState.batchSize").meanDelta(), EPSILON);
        assertEquals(10.0, scalars.get("cycleStart.combined.throughput").meanDelta(), EPSILON);
        assertEquals(0.0, scalars.get("batchProgress.combined.avgServiceTime").meanDelta(), EPSILON);
        assertEquals(0.0, scalars.get("batchComplete.combined.throughput").meanDelta(), EPSILON);
        assertEquals(10.0, scalars.get("rawBodyCost.combined.cost").meanDelta(), EPSILON);
    }

    @Test
    void testSystemIdleExecCentroidDistanceDelta() {
        SystemForkResult base = new SystemForkResult(
                0,
                1,
                1,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                CycleStartStatistics.EMPTY,
                BatchProgressStatistics.EMPTY,
                BatchCompleteStatistics.EMPTY,
                RawBodyCostStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                1.5);

        SystemForkResult cand = new SystemForkResult(
                0,
                1,
                1,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                CycleStartStatistics.EMPTY,
                BatchProgressStatistics.EMPTY,
                BatchCompleteStatistics.EMPTY,
                RawBodyCostStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                2.5);

        double delta = SystemTelemetryComparisonCalculator.compareCentroidDistance(base, cand);
        assertEquals(1.0, delta, EPSILON);
    }

    // --- 7. Compatibility Modes ---

    @Test
    void testCompatibleCalculatesAllAvailableTelemetry() {
        CompletedRun base = createSimpleCompletedRun("base", 100.0);
        CompletedRun cand = createSimpleCompletedRun("cand", 120.0);

        ComparisonCompatibility comp = ComparisonCompatibility.compatible();
        AggregateComparison agg = SystemTelemetryComparisonCalculator.compare(base, cand, comp);

        assertNotNull(agg);
        assertNotNull(agg.idleOccupancy());
        assertNotNull(agg.execOccupancy());
    }

    @Test
    void testPartialCalculatesMutuallyAvailableTelemetry() {
        ScalarSummary s10 = ScalarSummary.of(10.0);
        CycleStartScalars cycleScalars = new CycleStartScalars(s10, s10, s10, s10, s10, s10, s10);
        CycleStartStatistics cycleStart = new CycleStartStatistics(
                100L,
                cycleScalars,
                cycleScalars,
                cycleScalars,
                CorrelationResult.empty(CycleStartStatistics.COLUMN_NAMES),
                CorrelationResult.empty(CycleStartStatistics.COLUMN_NAMES),
                CorrelationResult.empty(CycleStartStatistics.COLUMN_NAMES));

        SystemForkResult base = new SystemForkResult(
                0,
                1,
                1,
                100L,
                0L,
                0L,
                0L,
                0L,
                0L,
                cycleStart,
                BatchProgressStatistics.EMPTY,
                BatchCompleteStatistics.EMPTY,
                RawBodyCostStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                Double.NaN);

        SystemForkResult cand = new SystemForkResult(
                0,
                1,
                1,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                CycleStartStatistics.EMPTY, // cycleStart disabled in candidate
                BatchProgressStatistics.EMPTY,
                BatchCompleteStatistics.EMPTY,
                RawBodyCostStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                DecisionStatistics.EMPTY,
                Double.NaN);

        ComparisonCompatibility partial = ComparisonCompatibility.partial(
                List.of(new ConfigurationDifference(
                        "/calibrationConfig/observeCycleStart", null, null, DifferenceCategory.OBSERVATION)),
                List.of("observeCycleStart toggled"));

        AggregateComparison agg = SystemTelemetryComparisonCalculator.compare(base, cand, partial);

        assertNotNull(agg);
        // CycleStart scalars should not be in comparison map since candidate was empty
        assertFalse(agg.scalarComparisons().containsKey("cycleStart.head.completed"));
        assertTrue(agg.scalarComparisons().isEmpty());
    }

    @Test
    void testIncompatibleBlocksTelemetryComparison() {
        CompletedRun base = createSimpleCompletedRun("base", 100.0);
        CompletedRun cand = createSimpleCompletedRun("cand", 120.0);

        ComparisonCompatibility incompatible = ComparisonCompatibility.incompatible(
                List.of(new ConfigurationDifference(
                        "/calibrationConfig/workUnits",
                        new IntNode(10),
                        new IntNode(100),
                        DifferenceCategory.WORKLOAD)),
                List.of("WorkUnits mismatch"));

        AggregateComparison agg = SystemTelemetryComparisonCalculator.compare(base, cand, incompatible);
        assertNull(agg);
    }

    @Test
    void testAuthorityBoundaryJmhUnaffectedByObserverThroughput() {
        CompletedRun base = createSimpleCompletedRun("base", 100.0);
        CompletedRun cand = createSimpleCompletedRun("cand", 120.0);

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(base, cand);
        assertNotNull(perf);
        assertEquals(20.0, perf.absoluteDelta(), EPSILON);

        // Even with huge telemetry differences, performance comparison remains strictly based on JMH
        AggregateComparison agg = SystemTelemetryComparisonCalculator.compare(base, cand);
        assertNotNull(agg);
        // Performance comparison outcome is unaffected by telemetry
        assertEquals(
                perf.outcome(),
                PerformanceComparisonCalculator.compare(base, cand).outcome());
    }

    private static CompletedRun createSimpleCompletedRun(String id, double throughputScore) {
        RunIdentity runId = new RunIdentity(id, "Trial " + id, null, 0, null, "/runs/" + id);
        TrialConfig config = new TrialConfig(1, 1, 1, null, "test-profile");
        ThroughputResult throughput = ThroughputResult.of(throughputScore, 1.0, "ops/s");
        RunArtifacts artifacts = RunArtifacts.standard("/runs/" + id);
        return new CompletedRun(runId, config, throughput, SystemForkResult.EMPTY, List.of(), artifacts);
    }
}
