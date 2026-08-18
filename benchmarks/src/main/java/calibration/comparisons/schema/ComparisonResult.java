package calibration.comparisons.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/// Top-level comparison result for a baseline and one or more candidate runs.
public record ComparisonResult(
        @NonNull CompletedRun baseline,
        @NonNull List<CompletedRun> candidates,
        @NonNull List<CandidateComparison> comparisons) {

    public ComparisonResult {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(comparisons, "comparisons must not be null");

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates list must not be empty");
        }
        if (comparisons.isEmpty()) {
            throw new IllegalArgumentException("comparisons list must not be empty");
        }
        if (candidates.size() != comparisons.size()) {
            throw new IllegalArgumentException("candidates size (" + candidates.size()
                    + ") must match comparisons size (" + comparisons.size() + ")");
        }

        candidates = List.copyOf(candidates);
        comparisons = List.copyOf(comparisons);

        for (CompletedRun candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate completed run must not be null");
        }

        Set<String> candidatePaths = new HashSet<>();
        for (CompletedRun candidate : candidates) {
            if (!candidatePaths.add(candidate.identity().sourcePath())) {
                throw new IllegalArgumentException(
                        "duplicate candidate run path: " + candidate.identity().sourcePath());
            }
        }
    }
}
