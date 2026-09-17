package calibration.statistics.iteration;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Observer statistics aggregated across participating cores in one JMH iteration.
public record SystemIterationResult(
        int iterationIndex,
        int participatingCoreCount,
        long batchProgressTotal,
        long batchCompleteTotal,
        @NonNull BatchProgressStatistics batchProgress,
        @NonNull BatchCompleteStatistics batchComplete) {

    public static final String TSV_HEADER = "iteration\tscope\tcore\tbatchProgressTotal\tbatchCompleteTotal\n";

    public SystemIterationResult {
        Objects.requireNonNull(batchProgress, "batchProgress must not be null");
        Objects.requireNonNull(batchComplete, "batchComplete must not be null");
    }

    public static SystemIterationResult empty(int iterationIndex, int participatingCoreCount) {
        return new SystemIterationResult(
                iterationIndex,
                participatingCoreCount,
                0L,
                0L,
                BatchProgressStatistics.EMPTY,
                BatchCompleteStatistics.EMPTY);
    }

    public String toTsvRow() {
        return this.iterationIndex + "\tITERATION\t-1\t" + this.batchProgressTotal + "\t" + this.batchCompleteTotal;
    }
}
