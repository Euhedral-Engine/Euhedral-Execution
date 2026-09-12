package calibration.comparisons;

import calibration.config.ComparisonKeyConfig;
import calibration.config.ComparisonStrategy;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Output of ComparisonPairPlanner containing the ordered list of pairs to compare.
public record ComparisonPairPlan(
        @NonNull ComparisonStrategy strategy,
        @NonNull List<ComparisonPair> pairs,
        @Nullable ComparisonKeyConfig keyConfig,
        @NonNull List<ComparisonKey> unmatchedBaselineKeys,
        @NonNull List<ComparisonKey> unmatchedCandidateKeys) {

    public ComparisonPairPlan {
        Objects.requireNonNull(strategy, "strategy must not be null");
        pairs = pairs != null ? List.copyOf(pairs) : List.of();
        unmatchedBaselineKeys = unmatchedBaselineKeys != null ? List.copyOf(unmatchedBaselineKeys) : List.of();
        unmatchedCandidateKeys = unmatchedCandidateKeys != null ? List.copyOf(unmatchedCandidateKeys) : List.of();
    }

    public static ComparisonPairPlan of(@NonNull ComparisonStrategy strategy, @NonNull List<ComparisonPair> pairs) {
        return new ComparisonPairPlan(strategy, pairs, null, List.of(), List.of());
    }
}
