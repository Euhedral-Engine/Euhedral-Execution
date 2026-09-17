package calibration.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;

import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import org.junit.jupiter.api.Test;

class BenchmarkObserverTest {

    @Test
    void alignsBatchProgressAndCompletionRingBuffers() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(4);
        for (int sample = 1; sample <= 6; sample++) {
            metrics.recordBatchProgress(sample, 1, 2, 4, 3, 0, sample, sample * 10.0);
            metrics.recordBatchComplete(sample, 1, 2, 4, 3, 0, sample, sample * 10.0, sample * 100.0);
        }

        metrics.align();

        assertEquals(6L, metrics.batchProgressObservations);
        assertEquals(6L, metrics.batchCompleteObservations);
        assertEquals(30.0, metrics.batchProgressSteadyStateAvgServiceTime[0]);
        assertEquals(60.0, metrics.batchProgressSteadyStateAvgServiceTime[3]);
        assertEquals(300.0, metrics.batchCompleteSteadyStateThroughput[0]);
        assertEquals(600.0, metrics.batchCompleteSteadyStateThroughput[3]);
    }
}
