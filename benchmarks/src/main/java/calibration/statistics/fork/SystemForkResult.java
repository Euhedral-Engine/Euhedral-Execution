package calibration.statistics.fork;

import calibration.statistics.iteration.BatchCompleteStatistics;
import calibration.statistics.iteration.BatchProgressStatistics;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Observer statistics aggregated across all measurement iterations in one JMH fork.
public record SystemForkResult(
        int forkIndex,
        int measurementIterationCount,
        int participatingCoreCount,
        long batchProgressTotal,
        long batchCompleteTotal,
        @NonNull BatchProgressStatistics batchProgress,
        @NonNull BatchCompleteStatistics batchComplete) {

    public static final String TSV_HEADER = "iteration\tscope\tcore\tbatchProgressTotal\tbatchCompleteTotal\n";

    public SystemForkResult {
        Objects.requireNonNull(batchProgress, "batchProgress must not be null");
        Objects.requireNonNull(batchComplete, "batchComplete must not be null");
    }

    public static SystemForkResult empty(int forkIndex, int measurementIterationCount, int participatingCoreCount) {
        return new SystemForkResult(
                forkIndex,
                measurementIterationCount,
                participatingCoreCount,
                0L,
                0L,
                BatchProgressStatistics.EMPTY,
                BatchCompleteStatistics.EMPTY);
    }

    public String toTsvRow() {
        return "-1\tFORK\t-1\t" + this.batchProgressTotal + "\t" + this.batchCompleteTotal;
    }
}
