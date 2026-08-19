package calibration.comparisons.schema;

import calibration.comparisons.ComparisonKey;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Comparison evaluation between a baseline run and a candidate run in a comparison pair.
public record CandidateComparison(
        int pairIndex,
        @NonNull RunIdentity baseline,
        @NonNull RunIdentity candidate,
        @Nullable ComparisonKey comparisonKey,
        @NonNull ComparisonCompatibility compatibility,
        @NonNull List<ConfigurationDifference> configurationDifferences,
        @Nullable PerformanceComparison performance,
        @NonNull List<CoreComparison> cores,
        @Nullable AggregateComparison aggregate) {

    public CandidateComparison {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(compatibility, "compatibility must not be null");
        configurationDifferences = configurationDifferences == null ? List.of() : List.copyOf(configurationDifferences);
        cores = cores == null ? List.of() : List.copyOf(cores);
    }

    public CandidateComparison(
            @NonNull RunIdentity baseline,
            @NonNull RunIdentity candidate,
            @NonNull ComparisonCompatibility compatibility,
            @NonNull List<ConfigurationDifference> configurationDifferences,
            @Nullable PerformanceComparison performance,
            @NonNull List<CoreComparison> cores,
            @Nullable AggregateComparison aggregate) {
        this(0, baseline, candidate, null, compatibility, configurationDifferences, performance, cores, aggregate);
    }
}
