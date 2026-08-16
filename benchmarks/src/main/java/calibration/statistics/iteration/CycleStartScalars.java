package calibration.statistics.iteration;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Continuous scalar series for cycle-start observations.
public record CycleStartScalars(
        @NonNull ScalarSummary completed,
        @NonNull ScalarSummary batchSize,
        @NonNull ScalarSummary upstreamCount,
        @NonNull ScalarSummary registeredWorkers,
        @NonNull ScalarSummary workerRank,
        @NonNull ScalarSummary contention,
        @NonNull ScalarSummary throughput) {

    public static final CycleStartScalars EMPTY = new CycleStartScalars(
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY,
            ScalarSummary.EMPTY);

    public CycleStartScalars {
        Objects.requireNonNull(completed, "completed must not be null");
        Objects.requireNonNull(batchSize, "batchSize must not be null");
        Objects.requireNonNull(upstreamCount, "upstreamCount must not be null");
        Objects.requireNonNull(registeredWorkers, "registeredWorkers must not be null");
        Objects.requireNonNull(workerRank, "workerRank must not be null");
        Objects.requireNonNull(contention, "contention must not be null");
        Objects.requireNonNull(throughput, "throughput must not be null");
    }

    public static CycleStartScalars empty() {
        return EMPTY;
    }
}
