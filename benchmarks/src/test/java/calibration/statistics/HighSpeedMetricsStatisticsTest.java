package calibration.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.BatchCompleteStatistics;
import calibration.statistics.iteration.BatchProgressStatistics;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.RawBodyCostStatistics;
import calibration.statistics.iteration.ScalarSummary;
import calibration.statistics.iteration.SystemIterationResult;
import calibration.statistics.iteration.TransitionAnalysis;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void testDisabledObservationsYieldEmptyStatistics() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(16);
        // Only record execution decisions (like in contention band calibration where other observers are disabled)
        metrics.recordExec(1, 1, 1, 1, 10, 5.0);
        metrics.recordExec(2, 2, 2, 2, 20, 10.0);

        CoreIterationResult result = HighSpeedMetricsStatistics.calculate(0, 0, metrics);

        // Exec decisions are active
        assertEquals(2L, result.execDecisionTotal());
        assertFalse(result.execOccupancy().isEmpty());

        // Idle decisions and other observers were disabled / zero, must be EMPTY
        assertEquals(0L, result.idleDecisionTotal());
        assertEquals(0L, result.cycleStartTotal());
        assertEquals(0L, result.batchProgressTotal());
        assertEquals(0L, result.batchCompleteTotal());
        assertEquals(0L, result.rawBodyCostTotal());

        assertEquals(BranchOccupancyResult.EMPTY, result.idleOccupancy());
        assertEquals(BranchOccupancyResult.EMPTY, result.idleDecisions().occupancy());
        assertTrue(result.cycleStart().head().completed().isEmpty());
        assertTrue(result.batchProgress().head().upstreamCount().isEmpty());
        assertTrue(result.batchComplete().head().upstreamCount().isEmpty());
        assertTrue(result.rawBodyCost().head().isEmpty());
        assertTrue(Double.isNaN(result.centroidDistance()));
    }

    @Test
    void testSystemExactCountsEqualSumOfCoreCounts() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(8);
        m1.recordCycleStart(1, 1, 10, 5, 2, 4, 1, 100, 10.0);
        m1.recordBatchProgress(1, 1, 2, 4, 1, 100, 1.5);
        m1.recordBatchComplete(1, 1, 2, 4, 1, 100, 1.5, 10.0);
        m1.recordRawBodyCost(1, 1, 50);
        m1.recordIdle(1, 1, 0, 1, 50, 10.0);
        m1.recordExec(1, 1, 2, 3, 250, 30.0);

        HighSpeedMetrics m2 = new HighSpeedMetrics(8);
        m2.recordCycleStart(2, 2, 20, 5, 2, 4, 2, 200, 20.0);
        m2.recordCycleStart(3, 3, 30, 5, 2, 4, 2, 300, 30.0);
        m2.recordBatchProgress(2, 2, 2, 4, 2, 200, 2.5);
        m2.recordBatchComplete(2, 2, 2, 4, 2, 200, 2.5, 20.0);
        m2.recordRawBodyCost(2, 2, 70);
        m2.recordRawBodyCost(3, 3, 80);
        m2.recordIdle(2, 2, 1, 2, 150, 20.0);
        m2.recordIdle(3, 3, 1, 3, 160, 25.0);
        m2.recordExec(2, 2, 3, 4, 350, 40.0);

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1, m2));

        assertEquals(0, system.iterationIndex());
        assertEquals(2, system.participatingCoreCount());
        assertEquals(3L, system.cycleStartTotal());
        assertEquals(2L, system.batchProgressTotal());
        assertEquals(2L, system.batchCompleteTotal());
        assertEquals(3L, system.rawBodyCostTotal());
        assertEquals(3L, system.idleDecisionTotal());
        assertEquals(2L, system.execDecisionTotal());
    }

    @Test
    void testSystemOccupancySumsExactCountsAndNormalizesAfterward() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(4);
        m1.idleBranchDecisionTotal[0][0] = 10L;

        HighSpeedMetrics m2 = new HighSpeedMetrics(4);
        m2.idleBranchDecisionTotal[4][4] = 90L;

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1, m2));
        BranchOccupancyResult occ = system.idleOccupancy();

        assertEquals(100L, occ.totalCount());
        assertEquals(10L, occ.exactCounts()[0][0]);
        assertEquals(90L, occ.exactCounts()[4][4]);
        // Normalized probability must be 0.1 and 0.9, NOT 0.5 each (which unweighted core probability average would
        // yield)
        assertEquals(0.1, occ.normalizedOccupancy()[0][0], EPSILON);
        assertEquals(0.9, occ.normalizedOccupancy()[4][4], EPSILON);
    }

    @Test
    void testSystemCentroidDerivedFromAggregateOccupancyDiffersFromUnweightedMean() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(4);
        // Core 1 has 10 decisions at (0, 0) -> centroid (0.0, 0.0)
        m1.idleBranchDecisionTotal[0][0] = 10L;

        HighSpeedMetrics m2 = new HighSpeedMetrics(4);
        // Core 2 has 90 decisions at (4, 4) -> centroid (4.0, 4.0)
        m2.idleBranchDecisionTotal[4][4] = 90L;

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1, m2));
        BranchOccupancyResult occ = system.idleOccupancy();

        // System centroid = (0*10 + 4*90)/100 = 3.6 (not (0+4)/2 = 2.0)
        assertEquals(3.6, occ.contentionCentroid(), EPSILON);
        assertEquals(3.6, occ.bodyCentroid(), EPSILON);
    }

    @Test
    void testSystemTransitionMatrixSumsCountsAndDerivesSecondaryStatisticsAfterward() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(16);
        // Core 1: 10 transitions 0 -> 1
        for (int i = 0; i < 10; i++) {
            m1.recordIdle(i * 2 + 1, 1, 0, 0, 10, 1.0); // state 0
            m1.recordIdle(i * 2 + 2, 1, 0, 1, 10, 2.0); // state 1
        }

        HighSpeedMetrics m2 = new HighSpeedMetrics(16);
        // Core 2: 90 transitions 0 -> 2 (recorded within capacity)
        // With limit 128:
        HighSpeedMetrics m2Large = new HighSpeedMetrics(256);
        for (int i = 0; i < 90; i++) {
            m2Large.recordIdle(i * 2 + 1, 1, 0, 0, 10, 1.0); // state 0
            m2Large.recordIdle(i * 2 + 2, 1, 0, 2, 10, 3.0); // state 2
        }

        HighSpeedMetrics m1Large = new HighSpeedMetrics(256);
        for (int i = 0; i < 10; i++) {
            m1Large.recordIdle(i * 2 + 1, 1, 0, 0, 10, 1.0); // state 0
            m1Large.recordIdle(i * 2 + 2, 1, 0, 1, 10, 2.0); // state 1
        }

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1Large, m2Large));
        TransitionAnalysis head = system.idleHeadTransitions();

        // System counts must be sum of core transition counts
        assertEquals(10L, head.transitionCounts()[0][1]);
        assertEquals(90L, head.transitionCounts()[0][2]);

        // Probabilities normalized after sum: 10/100 = 0.1, 90/100 = 0.9
        assertEquals(0.1, head.transitionProbabilities()[0][1], EPSILON);
        assertEquals(0.9, head.transitionProbabilities()[0][2], EPSILON);

        // Dominant outgoing state from state 0 must be 2 with probability 0.9
        assertEquals(2, head.dominantOutgoingState(0));
        assertEquals(0.9, head.dominantOutgoingProbability(0), EPSILON);
    }

    @Test
    void testNoTransitionsCreatedBetweenDifferentCores() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(16);
        m1.recordIdle(1, 1, 0, 0, 10, 1.0); // state 0
        m1.recordIdle(2, 2, 2, 0, 10, 2.0); // state 10

        HighSpeedMetrics m2 = new HighSpeedMetrics(16);
        m2.recordIdle(3, 3, 4, 0, 10, 3.0); // state 20
        m2.recordIdle(4, 4, 4, 4, 10, 4.0); // state 24

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1, m2));
        TransitionAnalysis head = system.idleHeadTransitions();

        // Core 1 has 0->10 (1). Core 2 has 20->24 (1).
        assertEquals(1L, head.transitionCounts()[0][10]);
        assertEquals(1L, head.transitionCounts()[20][24]);
        // Must NOT have transition between core 1 last state (10) and core 2 first state (20)
        assertEquals(0L, head.transitionCounts()[10][20]);
    }

    @Test
    void testNoTransitionsCreatedBetweenHeadAndSteadyStateWindows() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(4);
        // Head: state 0
        m1.recordIdle(1, 1, 0, 0, 10, 1.0);
        m1.recordIdle(2, 2, 0, 0, 10, 1.0);
        m1.recordIdle(3, 3, 0, 0, 10, 1.0);
        m1.recordIdle(4, 4, 0, 0, 10, 1.0);

        // Middle: unobserved states
        for (int i = 5; i <= 20; i++) {
            m1.recordIdle(i, i, 2, 2, 10, 1.0);
        }

        // SteadyState: state 1
        m1.recordIdle(21, 21, 0, 1, 10, 1.0);
        m1.recordIdle(22, 22, 0, 1, 10, 1.0);
        m1.recordIdle(23, 23, 0, 1, 10, 1.0);
        m1.recordIdle(24, 24, 0, 1, 10, 1.0);

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1));

        assertEquals(3L, system.idleHeadTransitions().transitionCounts()[0][0]);
        assertEquals(0L, system.idleHeadTransitions().transitionCounts()[0][1]);
        assertEquals(3L, system.idleSteadyStateTransitions().transitionCounts()[1][1]);
        assertEquals(0L, system.idleSteadyStateTransitions().transitionCounts()[0][1]);
    }

    @Test
    void testSystemVectorFieldMeansAreTransitionCountWeighted() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(16);
        // Core 1: 1 transition from (0, 0) to (1, 1) -> deltaContention = +1, deltaBody = +1
        m1.recordIdle(1, 1, 0, 0, 10, 1.0);
        m1.recordIdle(2, 2, 1, 1, 10, 2.0);

        HighSpeedMetrics m2 = new HighSpeedMetrics(64);
        // Core 2: 9 transitions from (0, 0) to (3, 3) -> deltaContention = +3, deltaBody = +3
        for (int i = 0; i < 9; i++) {
            m2.recordIdle(i * 2 + 1, 1, 0, 0, 10, 1.0);
            m2.recordIdle(i * 2 + 2, 1, 3, 3, 10, 2.0);
        }

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1, m2));
        VectorCell cell = system.idleHeadVectorField().cell(0, 0);

        // Weighted mean: (1*1 + 9*3) / 10 = 28 / 10 = 2.8 (not (1+3)/2 = 2.0)
        assertTrue(cell.hasVector());
        assertEquals(10L, cell.transitionCount());
        assertEquals(2.8, cell.meanDeltaContention(), EPSILON);
        assertEquals(2.8, cell.meanDeltaBody(), EPSILON);
        assertEquals(Math.hypot(2.8, 2.8), cell.magnitude(), EPSILON);
    }

    @Test
    void testScalarSystemMeanAndVarianceMatchDirectPooledObservations() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(16);
        m1.recordCycleStart(1, 1, 10, 10, 1, 1, 0, 1, 10.0);
        m1.recordCycleStart(2, 2, 20, 20, 1, 1, 0, 1, 20.0);

        HighSpeedMetrics m2 = new HighSpeedMetrics(16);
        m2.recordCycleStart(3, 3, 30, 30, 1, 1, 0, 1, 30.0);
        m2.recordCycleStart(4, 4, 40, 40, 1, 1, 0, 1, 40.0);
        m2.recordCycleStart(5, 5, 50, 50, 1, 1, 0, 1, 50.0);

        double[] pooled = {10.0, 20.0, 30.0, 40.0, 50.0};
        ScalarSummary expected = ScalarSummary.of(pooled);

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1, m2));
        ScalarSummary headThroughput = system.cycleStart().head().throughput();

        assertEquals(expected.count(), headThroughput.count());
        assertEquals(expected.mean(), headThroughput.mean(), EPSILON);
        assertEquals(expected.variance(), headThroughput.variance(), EPSILON);
        assertEquals(expected.standardDeviation(), headThroughput.standardDeviation(), EPSILON);
        assertEquals(expected.min(), headThroughput.min(), EPSILON);
        assertEquals(expected.max(), headThroughput.max(), EPSILON);
        assertEquals(expected.median(), headThroughput.median(), EPSILON);
        assertEquals(expected.p25(), headThroughput.p25(), EPSILON);
        assertEquals(expected.p50(), headThroughput.p50(), EPSILON);
        assertEquals(expected.p75(), headThroughput.p75(), EPSILON);
        assertEquals(expected.p95(), headThroughput.p95(), EPSILON);
    }

    @Test
    void testSystemQuantilesMatchDirectPooledObservationsAndNotAveragedCoreQuantiles() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(16);
        // Core 1: 2 observations [10, 20] -> median = 15.0
        m1.recordCycleStart(1, 1, 10, 10, 1, 1, 0, 1, 10.0);
        m1.recordCycleStart(2, 2, 20, 20, 1, 1, 0, 1, 20.0);

        HighSpeedMetrics m2 = new HighSpeedMetrics(16);
        // Core 2: 8 observations [30, 40, 50, 60, 70, 80, 90, 100] -> median = 65.0
        for (int i = 3; i <= 10; i++) {
            m2.recordCycleStart(i, i, i * 10, i * 10, 1, 1, 0, 1, (double) (i * 10));
        }

        // Unweighted average of core medians would be (15 + 65) / 2 = 40.0
        // Pooled sample [10, 20, 30, 40, 50, 60, 70, 80, 90, 100] median is 55.0
        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1, m2));
        ScalarSummary headThroughput = system.cycleStart().head().throughput();

        assertEquals(10L, headThroughput.count());
        assertEquals(55.0, headThroughput.mean(), EPSILON);
        assertEquals(55.0, headThroughput.median(), EPSILON);
    }

    @Test
    void testSystemCorrelationsMatchDirectCorrelationOverPooledAlignedRows() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(16);
        for (int i = 1; i <= 3; i++) {
            m1.recordCycleStart(i, i, i * 10, i * 20, 1, 1, 0, i * 30, (double) (i * 100));
        }

        HighSpeedMetrics m2 = new HighSpeedMetrics(16);
        for (int i = 4; i <= 6; i++) {
            m2.recordCycleStart(i, i, i * 10, i * 20, 1, 1, 0, i * 30, (double) (i * 100));
        }

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1, m2));
        CorrelationResult corr = system.cycleStart().headCorrelations();

        assertFalse(corr.isEmpty());
        // completed vs throughput across 6 points with perfect linear relationship
        assertEquals(1.0, corr.pearsonMatrix()[0][6], EPSILON);
        assertEquals(1.0, corr.spearmanMatrix()[0][6], EPSILON);
    }

    @Test
    void testRawBodyTotalCostAndTotalObservationsAreExactSums() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(16);
        m1.recordRawBodyCost(1, 1, 40);
        m1.recordRawBodyCost(2, 2, 60);

        HighSpeedMetrics m2 = new HighSpeedMetrics(16);
        m2.recordRawBodyCost(3, 3, 100);
        m2.recordRawBodyCost(4, 4, 200);
        m2.recordRawBodyCost(5, 5, 300);

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1, m2));
        RawBodyCostStatistics rbc = system.rawBodyCost();

        assertEquals(5L, rbc.totalObservations());
        assertEquals(700L, rbc.totalCost()); // 40+60+100+200+300 = 700
        assertEquals(5L, rbc.head().count());
        assertEquals(140.0, rbc.head().mean(), EPSILON);
    }

    @Test
    void testIdleExecCentroidDistanceCalculatedFromSystemOccupancyCentroids() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(16);
        m1.idleBranchDecisionTotal[0][0] = 50L;
        m1.execBranchDecisionTotal[3][4] = 50L;

        HighSpeedMetrics m2 = new HighSpeedMetrics(16);
        m2.idleBranchDecisionTotal[0][0] = 50L;
        m2.execBranchDecisionTotal[3][4] = 50L;

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m1, m2));

        assertEquals(0.0, system.idleOccupancy().contentionCentroid(), EPSILON);
        assertEquals(0.0, system.idleOccupancy().bodyCentroid(), EPSILON);
        assertEquals(3.0, system.execOccupancy().contentionCentroid(), EPSILON);
        assertEquals(4.0, system.execOccupancy().bodyCentroid(), EPSILON);

        // hypot(3 - 0, 4 - 0) = 5.0
        assertEquals(5.0, system.centroidDistance(), EPSILON);
    }

    @Test
    void testEmptyAndNullCoresIgnoredCorrectlyInSystemCalculation() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(16);
        m1.recordRawBodyCost(1, 1, 100);

        // List with null and valid metric
        List<HighSpeedMetrics> list = new ArrayList<>();
        list.add(null);
        list.add(m1);
        list.add(null);

        calibration.statistics.iteration.SystemIterationResult system =
                HighSpeedMetricsStatistics.calculateSystem(3, list);

        assertEquals(3, system.iterationIndex());
        assertEquals(1, system.participatingCoreCount());
        assertEquals(1L, system.rawBodyCostTotal());

        // Null list
        calibration.statistics.iteration.SystemIterationResult nullSys =
                HighSpeedMetricsStatistics.calculateSystem(1, null);
        assertEquals(1, nullSys.iterationIndex());
        assertEquals(0, nullSys.participatingCoreCount());
        assertEquals(0L, nullSys.rawBodyCostTotal());
    }

    @Test
    void testSingleCoreSystemCalculationMatchesCoreStatistics() {
        HighSpeedMetrics m = new HighSpeedMetrics(16);
        m.recordCycleStart(1, 1, 10, 5, 2, 4, 1, 100, 10.0);
        m.recordCycleStart(2, 2, 20, 5, 2, 4, 1, 200, 20.0);
        m.recordBatchProgress(1, 1, 2, 4, 1, 100, 1.5);
        m.recordBatchComplete(1, 1, 2, 4, 1, 100, 1.5, 10.0);
        m.recordRawBodyCost(1, 1, 50);
        m.recordIdle(1, 1, 0, 1, 50, 10.0);
        m.recordIdle(2, 2, 1, 2, 150, 20.0);
        m.recordExec(1, 1, 2, 3, 250, 30.0);
        m.recordExec(2, 2, 3, 4, 350, 40.0);

        CoreIterationResult coreResult = HighSpeedMetricsStatistics.calculate(0, 0, m);
        calibration.statistics.iteration.SystemIterationResult systemResult =
                HighSpeedMetricsStatistics.calculateSystem(0, java.util.List.of(m));

        assertEquals(coreResult.cycleStartTotal(), systemResult.cycleStartTotal());
        assertEquals(coreResult.batchProgressTotal(), systemResult.batchProgressTotal());
        assertEquals(coreResult.batchCompleteTotal(), systemResult.batchCompleteTotal());
        assertEquals(coreResult.rawBodyCostTotal(), systemResult.rawBodyCostTotal());
        assertEquals(coreResult.idleDecisionTotal(), systemResult.idleDecisionTotal());
        assertEquals(coreResult.execDecisionTotal(), systemResult.execDecisionTotal());

        assertEquals(
                coreResult.cycleStart().head().throughput().mean(),
                systemResult.cycleStart().head().throughput().mean(),
                EPSILON);
        assertEquals(
                coreResult.idleOccupancy().contentionCentroid(),
                systemResult.idleOccupancy().contentionCentroid(),
                EPSILON);
        assertEquals(coreResult.centroidDistance(), systemResult.centroidDistance(), EPSILON);
        assertEquals(
                coreResult.idleHeadTransitions().transitionCounts()[1][7],
                systemResult.idleHeadTransitions().transitionCounts()[1][7]);
        assertEquals(
                coreResult.idleHeadVectorField().cell(0, 1).magnitude(),
                systemResult.idleHeadVectorField().cell(0, 1).magnitude(),
                EPSILON);
    }

    @Test
    void testForkOccupancySumsExactCountsAcrossCoresAndIterationsAndNormalizesAfterward() {
        HighSpeedMetrics iter0Core0 = new HighSpeedMetrics(4);
        iter0Core0.idleBranchDecisionTotal[0][0] = 10L;

        HighSpeedMetrics iter0Core1 = new HighSpeedMetrics(4);
        iter0Core1.idleBranchDecisionTotal[0][0] = 20L;

        HighSpeedMetrics iter1Core0 = new HighSpeedMetrics(4);
        iter1Core0.idleBranchDecisionTotal[4][4] = 30L;

        HighSpeedMetrics iter1Core1 = new HighSpeedMetrics(4);
        iter1Core1.idleBranchDecisionTotal[4][4] = 40L;

        List<List<HighSpeedMetrics>> forkIterations =
                List.of(List.of(iter0Core0, iter0Core1), List.of(iter1Core0, iter1Core1));

        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(0, forkIterations);
        BranchOccupancyResult occ = fork.idleOccupancy();

        assertEquals(2, fork.measurementIterationCount());
        assertEquals(2, fork.participatingCoreCount());
        assertEquals(100L, occ.totalCount());
        assertEquals(30L, occ.exactCounts()[0][0]);
        assertEquals(70L, occ.exactCounts()[4][4]);
        assertEquals(0.3, occ.normalizedOccupancy()[0][0], EPSILON);
        assertEquals(0.7, occ.normalizedOccupancy()[4][4], EPSILON);
    }

    @Test
    void testForkCentroidDerivedFromForkOccupancyDiffersFromAveragedIterationCentroids() {
        HighSpeedMetrics iter0 = new HighSpeedMetrics(4);
        iter0.idleBranchDecisionTotal[0][0] = 10L; // centroid (0.0, 0.0)

        HighSpeedMetrics iter1 = new HighSpeedMetrics(4);
        iter1.idleBranchDecisionTotal[4][4] = 90L; // centroid (4.0, 4.0)

        List<List<HighSpeedMetrics>> forkIterations = List.of(List.of(iter0), List.of(iter1));

        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(0, forkIterations);
        BranchOccupancyResult occ = fork.idleOccupancy();

        // Fork centroid = (0*10 + 4*90)/100 = 3.6 (not (0+4)/2 = 2.0)
        assertEquals(3.6, occ.contentionCentroid(), EPSILON);
        assertEquals(3.6, occ.bodyCentroid(), EPSILON);
    }

    @Test
    void testForkTransitionMatrixSumsCountsAndNormalizesAfterward() {
        HighSpeedMetrics iter0 = new HighSpeedMetrics(256);
        for (int i = 0; i < 10; i++) {
            iter0.recordIdle(i * 2 + 1, 1, 0, 0, 10, 1.0); // state 0
            iter0.recordIdle(i * 2 + 2, 1, 0, 1, 10, 2.0); // state 1
        }

        HighSpeedMetrics iter1 = new HighSpeedMetrics(256);
        for (int i = 0; i < 90; i++) {
            iter1.recordIdle(i * 2 + 1, 1, 0, 0, 10, 1.0); // state 0
            iter1.recordIdle(i * 2 + 2, 1, 0, 2, 10, 3.0); // state 2
        }

        List<List<HighSpeedMetrics>> forkIterations = List.of(List.of(iter0), List.of(iter1));
        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(0, forkIterations);
        TransitionAnalysis head = fork.idleHeadTransitions();

        assertEquals(10L, head.transitionCounts()[0][1]);
        assertEquals(90L, head.transitionCounts()[0][2]);
        assertEquals(0.1, head.transitionProbabilities()[0][1], EPSILON);
        assertEquals(0.9, head.transitionProbabilities()[0][2], EPSILON);
        assertEquals(2, head.dominantOutgoingState(0));
        assertEquals(0.9, head.dominantOutgoingProbability(0), EPSILON);
    }

    @Test
    void testNoTransitionsCreatedAcrossIterationBoundaries() {
        HighSpeedMetrics iter0 = new HighSpeedMetrics(16);
        iter0.recordIdle(1, 1, 0, 0, 10, 1.0); // state 0
        iter0.recordIdle(2, 2, 2, 0, 10, 2.0); // state 10

        HighSpeedMetrics iter1 = new HighSpeedMetrics(16);
        iter1.recordIdle(3, 3, 4, 0, 10, 3.0); // state 20
        iter1.recordIdle(4, 4, 4, 4, 10, 4.0); // state 24

        List<List<HighSpeedMetrics>> forkIterations = List.of(List.of(iter0), List.of(iter1));
        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(0, forkIterations);
        TransitionAnalysis head = fork.idleHeadTransitions();

        assertEquals(1L, head.transitionCounts()[0][10]);
        assertEquals(1L, head.transitionCounts()[20][24]);
        // Must NOT create synthetic transition between iter0 end (10) and iter1 start (20)
        assertEquals(0L, head.transitionCounts()[10][20]);
    }

    @Test
    void testForkVectorFieldsAreTransitionCountWeighted() {
        HighSpeedMetrics iter0 = new HighSpeedMetrics(16);
        // Iteration 0: 1 transition from (0, 0) to (1, 1) -> delta = +1
        iter0.recordIdle(1, 1, 0, 0, 10, 1.0);
        iter0.recordIdle(2, 2, 1, 1, 10, 2.0);

        HighSpeedMetrics iter1 = new HighSpeedMetrics(64);
        // Iteration 1: 9 transitions from (0, 0) to (3, 3) -> delta = +3
        for (int i = 0; i < 9; i++) {
            iter1.recordIdle(i * 2 + 1, 1, 0, 0, 10, 1.0);
            iter1.recordIdle(i * 2 + 2, 1, 3, 3, 10, 2.0);
        }

        List<List<HighSpeedMetrics>> forkIterations = List.of(List.of(iter0), List.of(iter1));
        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(0, forkIterations);
        VectorCell cell = fork.idleHeadVectorField().cell(0, 0);

        assertTrue(cell.hasVector());
        assertEquals(10L, cell.transitionCount());
        assertEquals(2.8, cell.meanDeltaContention(), EPSILON);
        assertEquals(2.8, cell.meanDeltaBody(), EPSILON);
        assertEquals(Math.hypot(2.8, 2.8), cell.magnitude(), EPSILON);
    }

    @Test
    void testForkScalarMeanVarianceAndQuantilesMatchPooledDirectCalculation() {
        HighSpeedMetrics iter0 = new HighSpeedMetrics(16);
        // Iteration 0: 2 samples [10, 20] -> median 15
        iter0.recordCycleStart(1, 1, 10, 10, 1, 1, 0, 1, 10.0);
        iter0.recordCycleStart(2, 2, 20, 20, 1, 1, 0, 1, 20.0);

        HighSpeedMetrics iter1 = new HighSpeedMetrics(16);
        // Iteration 1: 8 samples [30, 40, 50, 60, 70, 80, 90, 100] -> median 65
        for (int i = 3; i <= 10; i++) {
            iter1.recordCycleStart(i, i, i * 10, i * 10, 1, 1, 0, 1, (double) (i * 10));
        }

        List<List<HighSpeedMetrics>> forkIterations = List.of(List.of(iter0), List.of(iter1));
        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(0, forkIterations);
        ScalarSummary throughput = fork.cycleStart().head().throughput();

        assertEquals(10L, throughput.count());
        assertEquals(55.0, throughput.mean(), EPSILON);
        assertEquals(55.0, throughput.median(), EPSILON);
    }

    @Test
    void testForkCorrelationsMatchDirectPooledAlignedRowsWithoutCrossJoining() {
        HighSpeedMetrics iter0 = new HighSpeedMetrics(16);
        for (int i = 1; i <= 3; i++) {
            iter0.recordCycleStart(i, i, i * 10, i * 20, 1, 1, 0, i * 30, (double) (i * 100));
        }

        HighSpeedMetrics iter1 = new HighSpeedMetrics(16);
        for (int i = 4; i <= 6; i++) {
            iter1.recordCycleStart(i, i, i * 10, i * 20, 1, 1, 0, i * 30, (double) (i * 100));
        }

        List<List<HighSpeedMetrics>> forkIterations = List.of(List.of(iter0), List.of(iter1));
        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(0, forkIterations);
        CorrelationResult corr = fork.cycleStart().headCorrelations();

        assertFalse(corr.isEmpty());
        assertEquals(1.0, corr.pearsonMatrix()[0][6], EPSILON);
        assertEquals(1.0, corr.spearmanMatrix()[0][6], EPSILON);
    }

    @Test
    void testForkRawBodyTotalCostAndObservationsAreExactSums() {
        HighSpeedMetrics iter0 = new HighSpeedMetrics(16);
        iter0.recordRawBodyCost(1, 1, 40);
        iter0.recordRawBodyCost(2, 2, 60);

        HighSpeedMetrics iter1 = new HighSpeedMetrics(16);
        iter1.recordRawBodyCost(3, 3, 100);
        iter1.recordRawBodyCost(4, 4, 200);
        iter1.recordRawBodyCost(5, 5, 300);

        List<List<HighSpeedMetrics>> forkIterations = List.of(List.of(iter0), List.of(iter1));
        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(0, forkIterations);
        RawBodyCostStatistics rbc = fork.rawBodyCost();

        assertEquals(5L, rbc.totalObservations());
        assertEquals(700L, rbc.totalCost());
        assertEquals(5L, rbc.head().count());
        assertEquals(140.0, rbc.head().mean(), EPSILON);
    }

    @Test
    void testForkIdleExecCentroidDistanceCalculatedFromForkOccupancyCentroids() {
        HighSpeedMetrics iter0 = new HighSpeedMetrics(16);
        iter0.idleBranchDecisionTotal[0][0] = 50L;
        iter0.execBranchDecisionTotal[3][4] = 50L;

        HighSpeedMetrics iter1 = new HighSpeedMetrics(16);
        iter1.idleBranchDecisionTotal[0][0] = 50L;
        iter1.execBranchDecisionTotal[3][4] = 50L;

        List<List<HighSpeedMetrics>> forkIterations = List.of(List.of(iter0), List.of(iter1));
        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(0, forkIterations);

        assertEquals(0.0, fork.idleOccupancy().contentionCentroid(), EPSILON);
        assertEquals(0.0, fork.idleOccupancy().bodyCentroid(), EPSILON);
        assertEquals(3.0, fork.execOccupancy().contentionCentroid(), EPSILON);
        assertEquals(4.0, fork.execOccupancy().bodyCentroid(), EPSILON);
        assertEquals(5.0, fork.centroidDistance(), EPSILON);
    }

    @Test
    void testWarmupIterationsExcludedFromForkResultWhileHeadSamplesRetained() {
        HighSpeedMetrics warmup = new HighSpeedMetrics(16);
        warmup.recordCycleStart(1, 1, 1000, 1000, 1, 1, 0, 1, 1000.0);

        HighSpeedMetrics measurement = new HighSpeedMetrics(16);
        measurement.recordCycleStart(1, 1, 10, 10, 1, 1, 0, 1, 10.0);
        measurement.recordCycleStart(2, 2, 20, 20, 1, 1, 0, 1, 20.0);

        // Only measurement iteration passed to fork aggregation
        List<List<HighSpeedMetrics>> forkIterations = List.of(List.of(measurement));
        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(0, forkIterations);

        assertEquals(1, fork.measurementIterationCount());
        assertEquals(2L, fork.cycleStartTotal());
        // Head sample from measurement iteration is present
        assertEquals(2L, fork.cycleStart().head().throughput().count());
        assertEquals(15.0, fork.cycleStart().head().throughput().mean(), EPSILON);
    }

    @Test
    void testSingleMeasurementIterationProducesEquivalentForkAndSystemIterationResult() {
        HighSpeedMetrics m1 = new HighSpeedMetrics(16);
        m1.recordCycleStart(1, 1, 10, 5, 2, 4, 1, 100, 10.0);
        m1.recordIdle(1, 1, 0, 1, 50, 10.0);
        m1.recordExec(1, 1, 2, 3, 250, 30.0);

        HighSpeedMetrics m2 = new HighSpeedMetrics(16);
        m2.recordCycleStart(2, 2, 20, 5, 2, 4, 2, 200, 20.0);
        m2.recordIdle(2, 2, 1, 2, 150, 20.0);
        m2.recordExec(2, 2, 3, 4, 350, 40.0);

        List<HighSpeedMetrics> coreMetrics = List.of(m1, m2);
        SystemIterationResult iterResult = HighSpeedMetricsStatistics.calculateSystem(0, coreMetrics);
        SystemForkResult forkResult = HighSpeedMetricsStatistics.calculateSystemFork(0, List.of(coreMetrics));

        assertEquals(iterResult.cycleStartTotal(), forkResult.cycleStartTotal());
        assertEquals(iterResult.idleDecisionTotal(), forkResult.idleDecisionTotal());
        assertEquals(iterResult.execDecisionTotal(), forkResult.execDecisionTotal());
        assertEquals(
                iterResult.cycleStart().head().throughput().mean(),
                forkResult.cycleStart().head().throughput().mean(),
                EPSILON);
        assertEquals(
                iterResult.idleOccupancy().contentionCentroid(),
                forkResult.idleOccupancy().contentionCentroid(),
                EPSILON);
        assertEquals(
                iterResult.execOccupancy().contentionCentroid(),
                forkResult.execOccupancy().contentionCentroid(),
                EPSILON);
        assertEquals(iterResult.centroidDistance(), forkResult.centroidDistance(), EPSILON);
    }
}
