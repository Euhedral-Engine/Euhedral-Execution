package calibration.statistics.iteration;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Complete statistics for batch-progress observations across head, steadyState, and combined segments.
public record BatchProgressStatistics(
        long totalObservations,
        @NonNull BatchProgressScalars head,
        @NonNull BatchProgressScalars steadyState,
        @NonNull BatchProgressScalars combined,
        @NonNull CorrelationResult headCorrelations,
        @NonNull CorrelationResult steadyStateCorrelations,
        @NonNull CorrelationResult combinedCorrelations) {

    public static final String[] COLUMN_NAMES = {"contention", "avgServiceTime"};

    public static final BatchProgressStatistics EMPTY = new BatchProgressStatistics(
            0L,
            BatchProgressScalars.EMPTY,
            BatchProgressScalars.EMPTY,
            BatchProgressScalars.EMPTY,
            CorrelationResult.empty(COLUMN_NAMES),
            CorrelationResult.empty(COLUMN_NAMES),
            CorrelationResult.empty(COLUMN_NAMES));

    public BatchProgressStatistics {
        Objects.requireNonNull(head, "head must not be null");
        Objects.requireNonNull(steadyState, "steadyState must not be null");
        Objects.requireNonNull(combined, "combined must not be null");
        Objects.requireNonNull(headCorrelations, "headCorrelations must not be null");
        Objects.requireNonNull(steadyStateCorrelations, "steadyStateCorrelations must not be null");
        Objects.requireNonNull(combinedCorrelations, "combinedCorrelations must not be null");
    }

    public static BatchProgressStatistics empty() {
        return EMPTY;
    }
}
