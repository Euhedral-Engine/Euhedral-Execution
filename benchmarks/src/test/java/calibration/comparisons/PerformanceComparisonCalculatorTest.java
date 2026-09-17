package calibration.comparisons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ThroughputResult;
import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.TrialConfig;
import calibration.statistics.ComparisonOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerformanceComparisonCalculatorTest {

    @Test
    void comparesIndependentForkSamples() {
        CompletedRun baseline = run("baseline", List.of(98.0, 100.0, 102.0));
        CompletedRun candidate = run("candidate", List.of(148.0, 150.0, 152.0, 154.0));

        var comparison = PerformanceComparisonCalculator.compare(baseline, candidate);

        assertNotNull(comparison);
        assertEquals(3L, comparison.baselineForkSummary().count());
        assertEquals(4L, comparison.candidateForkSummary().count());
        assertEquals(ComparisonOutcome.B_BETTER, comparison.outcome());
    }

    private static CompletedRun run(String id, List<Double> forkScores) {
        CalibrationBenchmarkConfig benchmark = new CalibrationBenchmarkConfig(
                List.of(2, 4), 2, 0, 0, false, 100, 1_000, 8, false, false, 15_000L, 1_000_000L, null);
        TrialConfig trial = new TrialConfig(3, 1, 2, List.of(), benchmark);
        double score =
                forkScores.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return new CompletedRun(
                new RunIdentity(id, id, null, 0, "/tmp/" + id),
                trial,
                new ThroughputResult(score, "ops/s", forkScores));
    }
}
