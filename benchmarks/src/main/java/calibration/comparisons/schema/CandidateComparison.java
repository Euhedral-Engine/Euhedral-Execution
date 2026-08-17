package calibration.comparisons.schema;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Comparison evaluation between a baseline run and a single candidate run.
public record CandidateComparison(
        @NonNull RunIdentity baseline,
        @NonNull RunIdentity candidate,
        @NonNull ComparisonCompatibility compatibility,
        @NonNull List<ConfigurationDifference> configurationDifferences,
        @NonNull PerformanceComparison performance,
        @NonNull List<CoreComparison> cores,
        @Nullable AggregateComparison aggregate) {

    public CandidateComparison {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(compatibility, "compatibility must not be null");
        configurationDifferences = configurationDifferences == null ? List.of() : List.copyOf(configurationDifferences);
        Objects.requireNonNull(performance, "performance must not be null");
        cores = cores == null ? List.of() : List.copyOf(cores);
    }
}
