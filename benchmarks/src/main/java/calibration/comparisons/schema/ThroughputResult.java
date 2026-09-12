package calibration.comparisons.schema;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Immutable raw performance evidence for throughput.
public record ThroughputResult(
        double score,
        double scoreError,
        @NonNull String scoreUnit,
        @NonNull List<Double> forkScores,
        @NonNull List<Double> iterationScores) {

    public ThroughputResult {
        Objects.requireNonNull(scoreUnit, "scoreUnit must not be null");
        if (scoreUnit.isBlank()) {
            throw new IllegalArgumentException("scoreUnit must not be blank");
        }
        forkScores = forkScores == null ? List.of() : List.copyOf(forkScores);
        iterationScores = iterationScores == null ? List.of() : List.copyOf(iterationScores);
    }

    public static ThroughputResult of(double score, double scoreError, @NonNull String scoreUnit) {
        return new ThroughputResult(score, scoreError, scoreUnit, List.of(), List.of());
    }
}
