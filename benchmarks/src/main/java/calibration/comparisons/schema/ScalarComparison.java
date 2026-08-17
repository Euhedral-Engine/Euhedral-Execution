package calibration.comparisons.schema;

import calibration.statistics.iteration.ScalarSummary;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Comparison between matching baseline and candidate scalar summaries.
public record ScalarComparison(
        @NonNull ScalarSummary baseline,
        @NonNull ScalarSummary candidate,
        double meanDelta,
        double medianDelta,
        double varianceDelta,
        double standardDeviationDelta,
        double cvDelta,
        double minDelta,
        double maxDelta,
        double p25Delta,
        double p50Delta,
        double p75Delta,
        double p95Delta,
        double iqrDelta,
        double normalizedIqrDelta,
        double p95ToP50RatioDelta) {

    public ScalarComparison {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
    }
}
