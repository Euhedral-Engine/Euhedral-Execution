package calibration.comparisons;

import calibration.comparisons.schema.CompletedRun;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Represents one planned comparison pairing between a baseline completed run and a candidate completed run.
public record ComparisonPair(
        int pairIndex,
        @NonNull CompletedRun baseline,
        @NonNull CompletedRun candidate,
        @Nullable ComparisonKey key) {

    public ComparisonPair {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
    }

    public ComparisonPair(int pairIndex, @NonNull CompletedRun baseline, @NonNull CompletedRun candidate) {
        this(pairIndex, baseline, candidate, null);
    }
}
