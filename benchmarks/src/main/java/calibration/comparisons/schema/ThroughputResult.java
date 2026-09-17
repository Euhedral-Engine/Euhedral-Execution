package calibration.comparisons.schema;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Immutable raw performance evidence for throughput.
public record ThroughputResult(
        double score, @NonNull String scoreUnit, @NonNull List<Double> forkScores) {

    public ThroughputResult {
        Objects.requireNonNull(scoreUnit, "scoreUnit must not be null");
        if (scoreUnit.isBlank()) {
            throw new IllegalArgumentException("scoreUnit must not be blank");
        }
        forkScores = forkScores == null ? List.of() : List.copyOf(forkScores);
    }
}
