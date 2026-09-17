package calibration.statistics.iteration;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Observer statistics for one physical core in one JMH iteration.
public record CoreIterationResult(
        int iterationIndex,
        int core,
        long batchProgressTotal,
        long batchCompleteTotal,
        @NonNull BatchProgressStatistics batchProgress,
        @NonNull BatchCompleteStatistics batchComplete) {

    public static final String TSV_HEADER = "iteration\tscope\tcore\tbatchProgressTotal\tbatchCompleteTotal\n";

    public CoreIterationResult {
        Objects.requireNonNull(batchProgress, "batchProgress must not be null");
        Objects.requireNonNull(batchComplete, "batchComplete must not be null");
    }

    public static CoreIterationResult empty(int iterationIndex, int core) {
        return new CoreIterationResult(
                iterationIndex, core, 0L, 0L, BatchProgressStatistics.EMPTY, BatchCompleteStatistics.EMPTY);
    }

    public boolean isEmpty() {
        return this.batchProgressTotal == 0L && this.batchCompleteTotal == 0L;
    }

    public String toTsvRow() {
        return this.iterationIndex + "\tCORE\t" + this.core + "\t" + this.batchProgressTotal + "\t"
                + this.batchCompleteTotal;
    }
}
