package calibration.statistics.iteration;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Continuous scalar series for batch-progress observations.
public record BatchProgressScalars(
        @NonNull ScalarSummary upstreamCount,
        @NonNull ScalarSummary registeredWorkers,
        @NonNull ScalarSummary productiveHandleCount,
        @NonNull ScalarSummary productiveHandleRatio,
        @NonNull ScalarSummary workerRank,
        @NonNull ScalarSummary contention,
        @NonNull ScalarSummary avgServiceTime) {

    public static final BatchProgressScalars EMPTY = new BatchProgressScalars(
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY);

    public BatchProgressScalars {
        Objects.requireNonNull(upstreamCount, "upstreamCount must not be null");
        Objects.requireNonNull(registeredWorkers, "registeredWorkers must not be null");
        Objects.requireNonNull(productiveHandleCount, "productiveHandleCount must not be null");
        Objects.requireNonNull(productiveHandleRatio, "productiveHandleRatio must not be null");
        Objects.requireNonNull(workerRank, "workerRank must not be null");
        Objects.requireNonNull(contention, "contention must not be null");
        Objects.requireNonNull(avgServiceTime, "avgServiceTime must not be null");
    }

    public BatchProgressScalars(
            ScalarSummary upstreamCount,
            ScalarSummary registeredWorkers,
            ScalarSummary workerRank,
            ScalarSummary contention,
            ScalarSummary avgServiceTime) {
        this(
                upstreamCount,
                registeredWorkers,
                ScalarSummary.EMPTY,
                ScalarSummary.EMPTY,
                workerRank,
                contention,
                avgServiceTime);
    }

    public static BatchProgressScalars empty() {
        return EMPTY;
    }
}
