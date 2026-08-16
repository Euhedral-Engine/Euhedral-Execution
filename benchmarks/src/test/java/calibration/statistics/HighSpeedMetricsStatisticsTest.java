package calibration.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.statistics.iteration.BatchCompleteStatistics;
import calibration.statistics.iteration.BatchProgressStatistics;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.RawBodyCostStatistics;
import calibration.statistics.iteration.ScalarSummary;
import calibration.statistics.iteration.TransitionAnalysis;
import org.junit.jupiter.api.Test;

class HighSpeedMetricsStatisticsTest {

    private static final double EPSILON = 1e-6;

    @Test
    void testAlignOccursBeforeSequenceAnalysis() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(4);
        // Record 6 idle decisions in ring buffer:
        // samples 1..6: states 0->1, 1->2, 2->3, 3->4, 4->0, 0->1
        metrics.recordIdle(1, 1, 0, 0, 10, 1.0); // state 0
        metrics.recordIdle(2, 2, 0, 1, 10, 2.0); // state 1
        metrics.recordIdle(3, 3, 0, 2, 10, 3.0); // state 2
        metrics.recordIdle(4, 4, 0, 3, 10, 4.0); // state 3
        metrics.recordIdle(5, 5, 0, 4, 10, 5.0); // state 4
        metrics.recordIdle(6, 6, 1, 0, 10, 6.0); // state 5 (contention=1, body=0 -> 1*5+0=5)

        // Without manual align, calculate should automatically align before sequence analysis
        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);

        // SteadyState should contain chronologically: state 2 -> state 3 -> state 4 -> state 5
        // Transitions: 2->3 (1), 3->4 (1), 4->5 (1)
        TransitionAnalysis SteadyStateTransitions = result.idleSteadyStateTransitions();
        assertEquals(1L, SteadyStateTransitions.transitionCounts()[2][3]);
        assertEquals(1L, SteadyStateTransitions.transitionCounts()[3][4]);
        assertEquals(1L, SteadyStateTransitions.transitionCounts()[4][5]);
        assertEquals(0L, SteadyStateTransitions.transitionCounts()[0][1]); // 0->1 was in head, not steadyState
    }

    @Test
    void testHeadAndSteadyStateStatisticsAreIndependent() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(4);
        // Head: values 10, 20, 30, 40
        metrics.recordCycleStart(1, 1, 10, 10, 1, 1, 0, 1, 10.0);
        metrics.recordCycleStart(2, 2, 20, 20, 1, 1, 0, 1, 20.0);
        metrics.recordCycleStart(3, 3, 30, 30, 1, 1, 0, 1, 30.0);
        metrics.recordCycleStart(4, 4, 40, 40, 1, 1, 0, 1, 40.0);

        // Middle and steadyState: 100 extra values ending in 110, 120, 130, 140
        for (int i = 5; i <= 100; i++) {
            metrics.recordCycleStart(i, i, i, i, 1, 1, 0, 1, (double) i);
        }
        metrics.recordCycleStart(101, 101, 110, 110, 1, 1, 0, 1, 110.0);
        metrics.recordCycleStart(102, 102, 120, 120, 1, 1, 0, 1, 120.0);
        metrics.recordCycleStart(103, 103, 130, 130, 1, 1, 0, 1, 130.0);
        metrics.recordCycleStart(104, 104, 140, 140, 1, 1, 0, 1, 140.0);

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 2, metrics);

        assertEquals(104L, result.cycleStartTotal());
        assertEquals(4L, result.cycleStart().head().throughput().count());
        assertEquals(25.0, result.cycleStart().head().throughput().mean(), EPSILON);

        assertEquals(4L, result.cycleStart().steadyState().throughput().count());
        assertEquals(125.0, result.cycleStart().steadyState().throughput().mean(), EPSILON);

        assertEquals(8L, result.cycleStart().combined().throughput().count());
        assertEquals(75.0, result.cycleStart().combined().throughput().mean(), EPSILON);
    }

    @Test
    void testExactOccupancyUsesBranchTotalsNotRawSampleCount() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(4);
        // Record only 2 raw decisions
        metrics.recordIdle(1, 1, 0, 0, 10, 1.0);
        metrics.recordIdle(2, 2, 0, 0, 10, 1.0);

        // Feed exact branch totals directly
        metrics.idleBranchDecisionTotal[0][0] = 30000L;
        metrics.idleBranchDecisionTotal[1][1] = 20000L;

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 1, metrics);

        // Exact occupancy must reflect 50,000 branch decisions
        assertEquals(50000L, result.idleOccupancy().totalCount());
        assertEquals(30000L, result.idleOccupancy().exactCounts()[0][0]);
        assertEquals(20000L, result.idleOccupancy().exactCounts()[1][1]);
        assertEquals(0.6, result.idleOccupancy().normalizedOccupancy()[0][0], EPSILON);
        assertEquals(0.4, result.idleOccupancy().normalizedOccupancy()[1][1], EPSILON);

        // Raw sample count remains 2
        assertEquals(2L, result.idleDecisions().head().contention().count());
    }

    @Test
    void testKnown5x5MeshProducesExpectedCentroidAndSpread() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(4);
        // 10 decisions at (1, 1) and 10 decisions at (3, 3)
        metrics.idleBranchDecisionTotal[1][1] = 10L;
        metrics.idleBranchDecisionTotal[3][3] = 10L;

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);
        BranchOccupancyResult occ = result.idleOccupancy();

        assertEquals(20L, occ.totalCount());
        assertEquals(2.0, occ.contentionCentroid(), EPSILON);
        assertEquals(2.0, occ.bodyCentroid(), EPSILON);
        assertEquals(1.0, occ.contentionVariance(), EPSILON);
        assertEquals(1.0, occ.bodyVariance(), EPSILON);
        assertEquals(1.0, occ.contentionBodyCovariance(), EPSILON);
        assertEquals(2.0, occ.radiusSquared(), EPSILON);
        assertEquals(Math.sqrt(2.0), occ.radius(), EPSILON);
    }

    @Test
    void testIdleAndExecutionCentroidDistance() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(4);
        // Exec at (4, 4)
        metrics.execBranchDecisionTotal[4][4] = 100L;
        // Idle at (1, 0)
        metrics.idleBranchDecisionTotal[1][0] = 100L;

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);

        assertEquals(1.0, result.idleOccupancy().contentionCentroid(), EPSILON);
        assertEquals(0.0, result.idleOccupancy().bodyCentroid(), EPSILON);

        assertEquals(4.0, result.execOccupancy().contentionCentroid(), EPSILON);
        assertEquals(4.0, result.execOccupancy().bodyCentroid(), EPSILON);

        // sqrt((4-1)^2 + (4-0)^2) = sqrt(9 + 16) = 5.0
        assertEquals(5.0, result.centroidDistance(), EPSILON);
    }

    @Test
    void testKnownAtoBtoASequenceProducesExpectedTransitions() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(16);
        // State A = (0, 0) = 0
        // State B = (1, 1) = 6
        // Sequence: A -> B -> A -> B
        metrics.recordIdle(1, 1, 0, 0, 10, 1.0);
        metrics.recordIdle(2, 2, 1, 1, 10, 2.0);
        metrics.recordIdle(3, 3, 0, 0, 10, 1.0);
        metrics.recordIdle(4, 4, 1, 1, 10, 2.0);

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);
        TransitionAnalysis trans = result.idleHeadTransitions();

        assertEquals(2L, trans.transitionCounts()[0][6]);
        assertEquals(1L, trans.transitionCounts()[6][0]);
        assertEquals(1.0, trans.oscillation(0, 6), EPSILON);
    }

    @Test
    void testHeadSteadyStateGapDoesNotCreateFalseTransition() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(4);
        // 4 head decisions in state 0 = (0, 0)
        metrics.recordIdle(1, 1, 0, 0, 10, 1.0);
        metrics.recordIdle(2, 2, 0, 0, 10, 1.0);
        metrics.recordIdle(3, 3, 0, 0, 10, 1.0);
        metrics.recordIdle(4, 4, 0, 0, 10, 1.0);

        // 10 unobserved middle decisions
        for (int i = 5; i <= 14; i++) {
            metrics.recordIdle(i, i, 2, 2, 10, 2.0);
        }

        // 4 steadyState decisions in state 1 = (0, 1)
        metrics.recordIdle(15, 15, 0, 1, 10, 1.0);
        metrics.recordIdle(16, 16, 0, 1, 10, 1.0);
        metrics.recordIdle(17, 17, 0, 1, 10, 1.0);
        metrics.recordIdle(18, 18, 0, 1, 10, 1.0);

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);

        TransitionAnalysis head = result.idleHeadTransitions();
        TransitionAnalysis SteadyState = result.idleSteadyStateTransitions();

        // Head has only 0->0 transitions
        assertEquals(3L, head.transitionCounts()[0][0]);
        assertEquals(0L, head.transitionCounts()[0][1]);

        // SteadyState has only 1->1 transitions
        assertEquals(3L, SteadyState.transitionCounts()[1][1]);
        assertEquals(0L, SteadyState.transitionCounts()[0][1]);
    }

    @Test
    void testKnownDirectionalSequenceProducesExpectedVectorField() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(16);
        // Transition: (0, 0) -> (2, 4)
        // deltaContention = 2, deltaBody = 4
        metrics.recordIdle(1, 1, 0, 0, 10, 1.0);
        metrics.recordIdle(2, 2, 2, 4, 10, 2.0);

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);
        VectorField vf = result.idleHeadVectorField();
        VectorCell cell = vf.cell(0, 0);

        assertTrue(cell.hasVector());
        assertEquals(1L, cell.transitionCount());
        assertEquals(2.0, cell.meanDeltaContention(), EPSILON);
        assertEquals(4.0, cell.meanDeltaBody(), EPSILON);
        assertEquals(Math.hypot(2.0, 4.0), cell.magnitude(), EPSILON);
    }

    @Test
    void testCorrelationMatricesUseFieldsFromSameCallbackRows() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(16);
        // Linear relationship: completed = x, batchSize = 2*x, contention = 3*x, throughput = 10*x
        for (int i = 1; i <= 5; i++) {
            metrics.recordCycleStart(i, i, i * 10, i * 20, 1, 1, 0, i * 30, (double) i * 100.0);
        }

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);
        CorrelationResult corr = result.cycleStart().headCorrelations();

        assertFalse(corr.isEmpty());
        // completed (col 0) vs throughput (col 6) -> perfect linear positive correlation (1.0)
        assertEquals(1.0, corr.pearsonMatrix()[0][6], EPSILON);
        assertEquals(1.0, corr.spearmanMatrix()[0][6], EPSILON);

        // batchSize (col 1) vs contention (col 5) -> 1.0
        assertEquals(1.0, corr.pearsonMatrix()[1][5], EPSILON);
    }

    @Test
    void testRawBodyQuantilesAreCorrect() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(256);
        for (int i = 1; i <= 100; i++) {
            metrics.recordRawBodyCost(i, i, i);
        }

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);
        RawBodyCostStatistics stats = result.rawBodyCost();

        assertEquals(100L, stats.totalObservations());
        assertEquals(5050L, stats.totalCost());

        ScalarSummary head = stats.head();
        assertEquals(100L, head.count());
        assertEquals(50.5, head.mean(), EPSILON);
        assertEquals(25.75, head.p25(), 1.0);
        assertEquals(50.5, head.p50(), 1.0);
        assertEquals(75.25, head.p75(), 1.0);
        assertEquals(95.05, head.p95(), 1.0);
        assertEquals(49.5, head.iqr(), 1.0);
    }

    @Test
    void testEmptyObservationTypesProduceExplicitUnavailableResults() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(16);
        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);

        assertEquals(0L, result.cycleStartTotal());
        assertEquals(0L, result.batchProgressTotal());
        assertEquals(0L, result.batchCompleteTotal());
        assertEquals(0L, result.rawBodyCostTotal());
        assertEquals(0L, result.idleDecisionTotal());
        assertEquals(0L, result.execDecisionTotal());

        assertTrue(result.cycleStart().head().completed().isEmpty());
        assertTrue(Double.isNaN(result.cycleStart().head().completed().mean()));
        assertTrue(Double.isNaN(result.centroidDistance()));
        assertTrue(result.idleOccupancy().isEmpty());
        assertEquals(0L, result.idleHeadTransitions().transitionCounts()[0][0]);
        assertTrue(result.cycleStart().headCorrelations().isEmpty());
    }

    @Test
    void testOneObservedStateDoesNotManufactureVarianceOrTransitions() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(16);
        metrics.recordIdle(1, 1, 2, 2, 50, 100.0);

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);

        ScalarSummary contention = result.idleDecisions().head().contention();
        assertEquals(1L, contention.count());
        assertEquals(50.0, contention.mean(), EPSILON);
        assertTrue(Double.isNaN(contention.variance()));
        assertTrue(Double.isNaN(contention.standardDeviation()));
        assertTrue(Double.isNaN(contention.coefficientOfVariation()));

        TransitionAnalysis trans = result.idleHeadTransitions();
        assertEquals(-1, trans.dominantOutgoingState(12)); // state 12 = 2*5+2
        assertEquals(0.0, trans.dominantOutgoingProbability(12), EPSILON);
        assertEquals(0.0, trans.selfTransitionRate(12), EPSILON);

        VectorCell cell = result.idleHeadVectorField().cell(2, 2);
        assertFalse(cell.hasVector());
        assertEquals(0L, cell.transitionCount());
    }

    @Test
    void testIterationAndCoreIdentitySurvivesCalculation() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(16);
        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(4, 11, metrics);

        assertEquals(4, result.iterationIndex());
        assertEquals(11, result.core());
    }

    @Test
    void testBatchProgressCalculations() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(16);
        for (int i = 1; i <= 5; i++) {
            metrics.recordBatchProgress(i, i, i * 2, 4, 0, i * 10, (double) i * 20.0);
        }

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(1, 3, metrics);
        BatchProgressStatistics bp = result.batchProgress();

        assertEquals(5L, bp.totalObservations());
        assertEquals(5L, bp.head().contention().count());
        assertEquals(30.0, bp.head().contention().mean(), EPSILON);
        assertEquals(60.0, bp.head().avgServiceTime().mean(), EPSILON);

        CorrelationResult corr = bp.headCorrelations();
        assertFalse(corr.isEmpty());
        // contention (col 0) vs avgServiceTime (col 1) -> 1.0
        assertEquals(1.0, corr.pearsonMatrix()[0][1], EPSILON);
    }

    @Test
    void testBatchCompleteCalculations() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(16);
        for (int i = 1; i <= 5; i++) {
            metrics.recordBatchComplete(i, i, i * 2, 4, 0, i * 10, (double) i * 20.0, (double) i * 100.0);
        }

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(1, 3, metrics);
        BatchCompleteStatistics bc = result.batchComplete();

        assertEquals(5L, bc.totalObservations());
        assertEquals(5L, bc.head().contention().count());
        assertEquals(30.0, bc.head().contention().mean(), EPSILON);
        assertEquals(60.0, bc.head().avgServiceTime().mean(), EPSILON);
        assertEquals(300.0, bc.head().throughput().mean(), EPSILON);

        CorrelationResult corr = bc.headCorrelations();
        assertFalse(corr.isEmpty());
        assertEquals(1.0, corr.pearsonMatrix()[0][1], EPSILON); // contention vs avgServiceTime
        assertEquals(1.0, corr.pearsonMatrix()[0][2], EPSILON); // contention vs throughput
    }

    @Test
    void testExecutionDecisionTransitionsAndVectorField() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(16);
        metrics.recordExec(1, 1, 1, 1, 10, 5.0); // state 6
        metrics.recordExec(2, 2, 3, 3, 20, 15.0); // state 18

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);
        TransitionAnalysis trans = result.execHeadTransitions();

        assertEquals(1L, trans.transitionCounts()[6][18]);
        VectorField vf = result.execHeadVectorField();
        VectorCell cell = vf.cell(1, 1);

        assertTrue(cell.hasVector());
        assertEquals(2.0, cell.meanDeltaContention(), EPSILON);
        assertEquals(2.0, cell.meanDeltaBody(), EPSILON);
    }

    @Test
    void testNullMetricsCalculation() {
        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(2, 5, null);
        assertEquals(2, result.iterationIndex());
        assertEquals(5, result.core());
        assertEquals(0L, result.cycleStartTotal());
        assertTrue(result.cycleStart().head().completed().isEmpty());
    }
}
