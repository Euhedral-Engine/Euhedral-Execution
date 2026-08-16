package calibration.statistics.iteration;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Complete statistics for batch-complete observations across head, steadyState, and combined segments.
public record BatchCompleteStatistics(
        long totalObservations,
        @NonNull BatchCompleteScalars head,
        @NonNull BatchCompleteScalars steadyState,
        @NonNull BatchCompleteScalars combined,
        @NonNull CorrelationResult headCorrelations,
        @NonNull CorrelationResult steadyStateCorrelations,
        @NonNull CorrelationResult combinedCorrelations) {

    public static final String[] COLUMN_NAMES = {"contention", "avgServiceTime", "throughput"};

    public static final BatchCompleteStatistics EMPTY = new BatchCompleteStatistics(
            0L,
            BatchCompleteScalars.EMPTY,
            BatchCompleteScalars.EMPTY,
            BatchCompleteScalars.EMPTY,
            CorrelationResult.empty(COLUMN_NAMES),
            CorrelationResult.empty(COLUMN_NAMES),
            CorrelationResult.empty(COLUMN_NAMES));

    public BatchCompleteStatistics {
        Objects.requireNonNull(head, "head must not be null");
        Objects.requireNonNull(steadyState, "steadyState must not be null");
        Objects.requireNonNull(combined, "combined must not be null");
        Objects.requireNonNull(headCorrelations, "headCorrelations must not be null");
        Objects.requireNonNull(steadyStateCorrelations, "steadyStateCorrelations must not be null");
        Objects.requireNonNull(combinedCorrelations, "combinedCorrelations must not be null");
    }

    public static BatchCompleteStatistics empty() {
        return EMPTY;
    }
}
