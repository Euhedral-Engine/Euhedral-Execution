package calibration.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.CoreIterationResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class HighSpeedMetricsStatisticsTest {

    @Test
    void calculatesOnlyRemainingObservationStreams() {
        HighSpeedMetrics metrics = metrics();

        CoreIterationResult core = HighSpeedMetricsStatistics.calculate(2, 7, metrics);
        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(3, List.of(List.of(metrics)));

        assertEquals(2L, core.batchProgressTotal());
        assertEquals(2L, core.batchCompleteTotal());
        assertEquals(15.0, core.batchProgress().combined().avgServiceTime().mean());
        assertEquals(150.0, core.batchComplete().combined().throughput().mean());
        assertEquals(1, fork.measurementIterationCount());
        assertEquals(1, fork.participatingCoreCount());
        assertEquals(2L, fork.batchProgressTotal());
        assertEquals(2L, fork.batchCompleteTotal());
    }

    static HighSpeedMetrics metrics() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(8);
        metrics.recordBatchProgress(1, 1, 2, 4, 2, 0, 3, 10.0);
        metrics.recordBatchProgress(2, 1, 2, 4, 4, 0, 5, 20.0);
        metrics.recordBatchComplete(1, 1, 2, 4, 2, 0, 3, 10.0, 100.0);
        metrics.recordBatchComplete(2, 1, 2, 4, 4, 0, 5, 20.0, 200.0);
        return metrics;
    }
}
