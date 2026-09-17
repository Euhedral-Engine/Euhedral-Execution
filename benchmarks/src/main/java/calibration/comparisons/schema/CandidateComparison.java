package calibration.comparisons.schema;

import calibration.comparisons.ComparisonKey;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Fork-throughput comparison for one planned baseline/candidate pair.
public record CandidateComparison(
        int pairIndex,
        @NonNull RunIdentity baseline,
        @NonNull RunIdentity candidate,
        @Nullable ComparisonKey comparisonKey,
        @NonNull List<ConfigurationDifference> configurationDifferences,
        @NonNull PerformanceComparison performance) {

    public CandidateComparison {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        configurationDifferences = configurationDifferences == null ? List.of() : List.copyOf(configurationDifferences);
        Objects.requireNonNull(performance, "performance must not be null");
    }
}
