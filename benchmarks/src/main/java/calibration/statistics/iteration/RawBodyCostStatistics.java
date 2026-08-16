package calibration.statistics.iteration;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Complete statistics for raw body-cost observations.
public record RawBodyCostStatistics(
        long totalObservations,
        long totalCost,
        @NonNull ScalarSummary head,
        @NonNull ScalarSummary steadyState,
        @NonNull ScalarSummary combined) {

    public static final RawBodyCostStatistics EMPTY =
            new RawBodyCostStatistics(0L, 0L, ScalarSummary.EMPTY, ScalarSummary.EMPTY, ScalarSummary.EMPTY);

    public RawBodyCostStatistics {
        Objects.requireNonNull(head, "head must not be null");
        Objects.requireNonNull(steadyState, "steadyState must not be null");
        Objects.requireNonNull(combined, "combined must not be null");
    }

    public static RawBodyCostStatistics empty() {
        return EMPTY;
    }
}
