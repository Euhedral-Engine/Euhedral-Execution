package calibration.comparisons.schema;

import calibration.statistics.ComparisonOutcome;
import calibration.statistics.iteration.ScalarSummary;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Performance comparison result evaluating throughput changes.
public record PerformanceComparison(
        @NonNull ThroughputResult baseline,
        @NonNull ThroughputResult candidate,
        double absoluteDelta,
        double relativeDeltaPercent,
        @NonNull ScalarSummary baselineForkSummary,
        @NonNull ScalarSummary candidateForkSummary,
        @NonNull ComparisonOutcome outcome) {

    public PerformanceComparison {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(baselineForkSummary, "baselineForkSummary must not be null");
        Objects.requireNonNull(candidateForkSummary, "candidateForkSummary must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
