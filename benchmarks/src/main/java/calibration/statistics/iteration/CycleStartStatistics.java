package calibration.statistics.iteration;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Complete statistics for cycle-start observations across head, steadyState, and combined segments.
public record CycleStartStatistics(
        long totalObservations,
        @NonNull CycleStartScalars head,
        @NonNull CycleStartScalars steadyState,
        @NonNull CycleStartScalars combined,
        @NonNull CorrelationResult headCorrelations,
        @NonNull CorrelationResult steadyStateCorrelations,
        @NonNull CorrelationResult combinedCorrelations) {

    public static final String[] COLUMN_NAMES = {
        "completed", "batchSize", "upstreamCount", "registeredWorkers", "workerRank", "contention", "throughput"
    };

    public static final CycleStartStatistics EMPTY = new CycleStartStatistics(
            0L,
            CycleStartScalars.EMPTY,
            CycleStartScalars.EMPTY,
            CycleStartScalars.EMPTY,
            CorrelationResult.empty(COLUMN_NAMES),
            CorrelationResult.empty(COLUMN_NAMES),
            CorrelationResult.empty(COLUMN_NAMES));

    public CycleStartStatistics {
        Objects.requireNonNull(head, "head must not be null");
        Objects.requireNonNull(steadyState, "steadyState must not be null");
        Objects.requireNonNull(combined, "combined must not be null");
        Objects.requireNonNull(headCorrelations, "headCorrelations must not be null");
        Objects.requireNonNull(steadyStateCorrelations, "steadyStateCorrelations must not be null");
        Objects.requireNonNull(combinedCorrelations, "combinedCorrelations must not be null");
    }

    public static CycleStartStatistics empty() {
        return EMPTY;
    }
}
