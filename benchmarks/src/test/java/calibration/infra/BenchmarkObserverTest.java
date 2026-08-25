package calibration.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;

import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import org.junit.jupiter.api.Test;

class BenchmarkObserverTest {

    @Test
    void testRawBodyCostRecordingWarmupAndSteadyState() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(4);
        metrics.recordRawBodyCost(1L, 10L, 100L);
        metrics.recordRawBodyCost(2L, 20L, 200L);
        metrics.recordRawBodyCost(3L, 30L, 300L);
        metrics.recordRawBodyCost(4L, 40L, 400L);
        metrics.recordRawBodyCost(5L, 50L, 500L);

        assertEquals(5L, metrics.rawBodyCostObservations);
        assertEquals(1500L, metrics.rawBodyCostTotal);

        // Warmup captures first 4 samples (limit=4)
        assertEquals(100L, metrics.rawBodyCostWarmupState[0][2]);
        assertEquals(200L, metrics.rawBodyCostWarmupState[1][2]);
        assertEquals(300L, metrics.rawBodyCostWarmupState[2][2]);
        assertEquals(400L, metrics.rawBodyCostWarmupState[3][2]);

        // Before align: SteadyState wrapped at index 0 (sample 5 at idx 0)
        assertEquals(500L, metrics.rawBodyCostSteadyStateState[0][2]);
        assertEquals(200L, metrics.rawBodyCostSteadyStateState[1][2]);
        assertEquals(300L, metrics.rawBodyCostSteadyStateState[2][2]);
        assertEquals(400L, metrics.rawBodyCostSteadyStateState[3][2]);

        metrics.align();

        // After align: SteadyState is chronologically oldest -> newest (200, 300, 400, 500)
        assertEquals(200L, metrics.rawBodyCostSteadyStateState[0][2]);
        assertEquals(300L, metrics.rawBodyCostSteadyStateState[1][2]);
        assertEquals(400L, metrics.rawBodyCostSteadyStateState[2][2]);
        assertEquals(500L, metrics.rawBodyCostSteadyStateState[3][2]);
    }

    @Test
    void testAlignUnderCapacity() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(8);
        metrics.recordRawBodyCost(1L, 10L, 10L);
        metrics.recordRawBodyCost(2L, 20L, 20L);
        metrics.recordRawBodyCost(3L, 30L, 30L);

        metrics.align();

        assertEquals(3L, metrics.rawBodyCostObservations);
        assertEquals(10L, metrics.rawBodyCostSteadyStateState[0][2]);
        assertEquals(20L, metrics.rawBodyCostSteadyStateState[1][2]);
        assertEquals(30L, metrics.rawBodyCostSteadyStateState[2][2]);
    }

    @Test
    void testSkippedBodyDecisionUsesFirstBodyColumn() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(4);

        metrics.recordIdle(1L, 10L, 0, -1, 100L, 25.0);

        assertEquals(1L, metrics.idleBranchDecisionTotal[0][0]);
        assertEquals(0L, metrics.idleWarmupDecisionState[0][3]);
        assertEquals(0L, metrics.idleSteadyStateDecisionState[0][3]);
    }

    @Test
    void testAlignCycleStartAndDecisions() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(4);
        for (int i = 1; i <= 6; i++) {
            metrics.recordCycleStart(i, i * 10, i * 100, 10, 1, 4, 0, i * 2, (double) i * 1000.0);
            metrics.recordIdle(i, i * 10, 1, 2, i * 5, (double) i * 50.0);
            metrics.recordExec(i, i * 10, 1, 4, i * 8, (double) i * 80.0);
        }

        metrics.align();

        // 6 samples recorded with limit 4, SteadyState should contain samples 3, 4, 5, 6
        assertEquals(300L, metrics.cycleStartSteadyStateState[0][2]);
        assertEquals(400L, metrics.cycleStartSteadyStateState[1][2]);
        assertEquals(500L, metrics.cycleStartSteadyStateState[2][2]);
        assertEquals(600L, metrics.cycleStartSteadyStateState[3][2]);
        assertEquals(3000.0, metrics.cycleStartSteadyStateThroughput[0]);
        assertEquals(6000.0, metrics.cycleStartSteadyStateThroughput[3]);

        assertEquals(150.0, metrics.idleSteadyStateSmoothedBodyCost[0]);
        assertEquals(300.0, metrics.idleSteadyStateSmoothedBodyCost[3]);

        assertEquals(240.0, metrics.execSteadyStateSmoothedBodyCost[0]);
        assertEquals(480.0, metrics.execSteadyStateSmoothedBodyCost[3]);
    }

    @Test
    void testAlignContentionStalenessSamples() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(7, 4);
        for (int i = 1; i <= 6; i++) {
            metrics.recordContentionStaleness(
                    i,
                    i * 10L,
                    i * 100L,
                    i * 50L,
                    i,
                    i * 10_000L,
                    i - 1L,
                    i * 1_000L,
                    i,
                    5_000L,
                    i * 2L,
                    i,
                    i * 3L,
                    1,
                    0L,
                    4L,
                    8,
                    2);
        }

        metrics.align();

        assertEquals(7, metrics.core);
        assertEquals(6L, metrics.contentionStalenessObservations);
        assertEquals(1L, metrics.contentionStalenessHead[0][0]);
        assertEquals(4L, metrics.contentionStalenessHead[3][0]);
        assertEquals(3L, metrics.contentionStalenessSteadyState[0][0]);
        assertEquals(6L, metrics.contentionStalenessSteadyState[3][0]);
        assertEquals(6_000L, metrics.contentionStalenessSteadyState[3][7]);
        assertEquals(18L, metrics.contentionStalenessSteadyState[3][12]);
    }

    @Test
    void productivityExclusionTelemetryRemainsAlignedWithContentionSamples() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(7, 2);
        metrics.recordContentionStaleness(
                1, 1, 900_000L, 900_000L, 1, 1, 0, 0, 1, 15_000L, 1, 0, 1, 0, 0, 1, 23, 12, true, 1, 110L, 90.0);
        metrics.recordContentionStaleness(
                2, 1, 900_000L, 900_000L, 2, 2, 0, 0, 2, 15_000L, 1, 0, 1, 0, 0, 1, 23, 12, true, 2, 111L, 91.0);
        metrics.recordContentionStaleness(
                3, 1, 900_000L, 900_000L, 3, 3, 0, 0, 0, -1L, 2, 0, 2, 0, 1, 1, 23, 12, false, 2, 112L, 92.0);

        metrics.align();

        assertEquals(1L, metrics.contentionStalenessSteadyState[0][18]);
        assertEquals(2L, metrics.contentionStalenessSteadyState[0][19]);
        assertEquals(0L, metrics.contentionStalenessSteadyState[1][18]);
        assertEquals(2L, metrics.contentionStalenessSteadyState[1][19]);
        assertEquals(112L, metrics.contentionStalenessSteadyState[1][20]);
        assertEquals(92.0, Double.longBitsToDouble(metrics.contentionStalenessSteadyState[1][21]));
    }

    @Test
    void testPullConvoySamplesAndPerHandleAggregatesAreBounded() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(7, 4, false, true);
        for (int i = 1; i <= 6; i++) {
            metrics.recordPullConvoy(i * 100L, 42L, 7, i % 2 == 0 ? 7 : -1, 3_000L, 1_500L, i, i % 2 == 0, i * 10L);
        }

        metrics.align();

        assertEquals(6L, metrics.pullConvoyObservations);
        assertEquals(1L, metrics.pullConvoyHandleCount);
        assertEquals(6L, metrics.pullConvoyHandleAttempts[0]);
        assertEquals(3L, metrics.pullConvoyHandleSuccesses[0]);
        assertEquals(3L, metrics.pullConvoyHandleFailures[0]);
        assertEquals(12L, metrics.pullConvoyHandleProducedFrames[0]);
        assertEquals(120L, metrics.pullConvoyHandleHoldTimeNs[0]);
        assertEquals(60L, metrics.pullConvoyHandleMaxHoldTimeNs[0]);
        assertEquals(300L, metrics.pullConvoySteadyState[0][0]);
        assertEquals(600L, metrics.pullConvoySteadyState[3][0]);
    }
}
