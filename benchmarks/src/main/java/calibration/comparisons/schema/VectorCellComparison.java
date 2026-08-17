package calibration.comparisons.schema;

import calibration.statistics.VectorCell;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Comparison between matching baseline and candidate vector cells.
public record VectorCellComparison(
        int contentionBand,
        int bodyBand,
        @NonNull VectorCell baseline,
        @NonNull VectorCell candidate,
        long transitionCountDelta,
        double meanDeltaContentionDelta,
        double meanDeltaBodyDelta,
        double magnitudeDelta) {

    public VectorCellComparison {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
    }
}
