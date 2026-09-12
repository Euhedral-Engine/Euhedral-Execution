package calibration.statistics.iteration;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Continuous scalar series for branch-decision observations.
public record DecisionScalars(
        @NonNull ScalarSummary contention, @NonNull ScalarSummary smoothedBodyCost) {

    public static final DecisionScalars EMPTY = new DecisionScalars(ScalarSummary.EMPTY, ScalarSummary.EMPTY);

    public DecisionScalars {
        Objects.requireNonNull(contention, "contention must not be null");
        Objects.requireNonNull(smoothedBodyCost, "smoothedBodyCost must not be null");
    }

    public static DecisionScalars empty() {
        return EMPTY;
    }
}
