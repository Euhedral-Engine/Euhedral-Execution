package calibration.statistics.iteration;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Continuous scalar series for batch-complete observations.
public record BatchCompleteScalars(
        @NonNull ScalarSummary upstreamCount,
        @NonNull ScalarSummary registeredWorkers,
        @NonNull ScalarSummary workerRank,
        @NonNull ScalarSummary contention,
        @NonNull ScalarSummary avgServiceTime,
        @NonNull ScalarSummary throughput) {

    public static final BatchCompleteScalars EMPTY = new BatchCompleteScalars(
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY);

    public BatchCompleteScalars {
        Objects.requireNonNull(upstreamCount, "upstreamCount must not be null");
        Objects.requireNonNull(registeredWorkers, "registeredWorkers must not be null");
        Objects.requireNonNull(workerRank, "workerRank must not be null");
        Objects.requireNonNull(contention, "contention must not be null");
        Objects.requireNonNull(avgServiceTime, "avgServiceTime must not be null");
        Objects.requireNonNull(throughput, "throughput must not be null");
    }

    public static BatchCompleteScalars empty() {
        return EMPTY;
    }
}
