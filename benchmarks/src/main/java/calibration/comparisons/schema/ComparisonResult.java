package calibration.comparisons.schema;

import calibration.comparisons.ComparisonKey;
import calibration.config.ComparisonKeyConfig;
import calibration.config.ComparisonStrategy;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Top-level post-run calibration comparison result encapsulating the strategy and all evaluated comparison pairs.
public record ComparisonResult(
        @NonNull ComparisonStrategy strategy,
        @NonNull List<CandidateComparison> comparisons,
        @Nullable ComparisonKeyConfig keyConfig,
        @NonNull List<ComparisonKey> unmatchedBaselineKeys,
        @NonNull List<ComparisonKey> unmatchedCandidateKeys) {

    public ComparisonResult {
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(comparisons, "comparisons must not be null");

        if (comparisons.isEmpty()) {
            throw new IllegalArgumentException("comparisons list must not be empty");
        }

        comparisons = List.copyOf(comparisons);
        for (CandidateComparison comp : comparisons) {
            Objects.requireNonNull(comp, "comparison element must not be null");
        }

        unmatchedBaselineKeys = unmatchedBaselineKeys != null ? List.copyOf(unmatchedBaselineKeys) : List.of();
        unmatchedCandidateKeys = unmatchedCandidateKeys != null ? List.copyOf(unmatchedCandidateKeys) : List.of();
    }

    public ComparisonResult(@NonNull ComparisonStrategy strategy, @NonNull List<CandidateComparison> comparisons) {
        this(strategy, comparisons, null, List.of(), List.of());
    }

    public ComparisonResult(@NonNull List<CandidateComparison> comparisons) {
        this(ComparisonStrategy.BASELINE, comparisons, null, List.of(), List.of());
    }

    public ComparisonResult(
            @NonNull CompletedRun baseline,
            @NonNull List<CompletedRun> candidates,
            @NonNull List<CandidateComparison> comparisons) {
        this(ComparisonStrategy.BASELINE, comparisons, null, List.of(), List.of());
    }
}
