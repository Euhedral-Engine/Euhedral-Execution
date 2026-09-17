package calibration.statistics;

import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.BatchCompleteScalars;
import calibration.statistics.iteration.BatchCompleteStatistics;
import calibration.statistics.iteration.BatchProgressScalars;
import calibration.statistics.iteration.BatchProgressStatistics;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.ScalarSummary;
import calibration.statistics.iteration.SystemIterationResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Calculates detached batch-progress and batch-complete observation statistics.
public final class HighSpeedMetricsStatistics {

    private static final int UPSTREAM = 0;
    private static final int WORKERS = 1;
    private static final int PRODUCTIVE = 2;
    private static final int PRODUCTIVE_RATIO = 3;
    private static final int RANK = 4;
    private static final int CONTENTION = 5;
    private static final int SERVICE_TIME = 6;
    private static final int THROUGHPUT = 7;

    private HighSpeedMetricsStatistics() {}

    public static @NonNull CoreIterationResult calculate(
            int iterationIndex, int core, @Nullable HighSpeedMetrics metrics) {
        if (metrics == null) {
            return CoreIterationResult.empty(iterationIndex, core);
        }
        metrics.align();
        List<HighSpeedMetrics> values = List.of(metrics);
        BatchProgressStatistics progress = calculateBatchProgress(values);
        BatchCompleteStatistics complete = calculateBatchComplete(values);
        return new CoreIterationResult(
                iterationIndex, core, progress.totalObservations(), complete.totalObservations(), progress, complete);
    }

    public static @NonNull SystemIterationResult calculateSystem(
            int iterationIndex, @Nullable Collection<HighSpeedMetrics> metrics) {
        List<HighSpeedMetrics> values = align(metrics);
        if (values.isEmpty()) {
            return SystemIterationResult.empty(iterationIndex, 0);
        }
        BatchProgressStatistics progress = calculateBatchProgress(values);
        BatchCompleteStatistics complete = calculateBatchComplete(values);
        return new SystemIterationResult(
                iterationIndex,
                values.size(),
                progress.totalObservations(),
                complete.totalObservations(),
                progress,
                complete);
    }

    public static @NonNull SystemForkResult calculateSystemFork(
            int forkIndex, @Nullable Collection<? extends Collection<HighSpeedMetrics>> measurementIterations) {
        if (measurementIterations == null || measurementIterations.isEmpty()) {
            return SystemForkResult.empty(forkIndex, 0, 0);
        }
        List<HighSpeedMetrics> all = new ArrayList<>();
        int iterationCount = 0;
        int coreCount = 0;
        for (Collection<HighSpeedMetrics> iteration : measurementIterations) {
            List<HighSpeedMetrics> values = align(iteration);
            if (!values.isEmpty()) {
                all.addAll(values);
                iterationCount++;
                coreCount = Math.max(coreCount, values.size());
            }
        }
        if (all.isEmpty()) {
            return SystemForkResult.empty(forkIndex, 0, 0);
        }
        BatchProgressStatistics progress = calculateBatchProgress(all);
        BatchCompleteStatistics complete = calculateBatchComplete(all);
        return new SystemForkResult(
                forkIndex,
                iterationCount,
                coreCount,
                progress.totalObservations(),
                complete.totalObservations(),
                progress,
                complete);
    }

    private static List<HighSpeedMetrics> align(@Nullable Collection<HighSpeedMetrics> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return List.of();
        }
        List<HighSpeedMetrics> values = new ArrayList<>(metrics.size());
        for (HighSpeedMetrics metric : metrics) {
            if (metric != null) {
                metric.align();
                values.add(metric);
            }
        }
        return values;
    }

    private static BatchProgressStatistics calculateBatchProgress(List<HighSpeedMetrics> metrics) {
        long total = metrics.stream()
                .mapToLong(value -> value.batchProgressObservations)
                .sum();
        if (total == 0L) {
            return BatchProgressStatistics.EMPTY;
        }
        Samples head = new Samples(false);
        Samples steady = new Samples(false);
        Samples combined = new Samples(false);
        for (HighSpeedMetrics metric : metrics) {
            int count = (int) Math.min(metric.batchProgressObservations, metric.rawSampleLimit);
            append(head, metric.batchProgressHeadState, metric.batchProgressHeadAvgServiceTime, null, count);
            append(
                    steady,
                    metric.batchProgressSteadyStateState,
                    metric.batchProgressSteadyStateAvgServiceTime,
                    null,
                    count);
            append(combined, metric.batchProgressHeadState, metric.batchProgressHeadAvgServiceTime, null, count);
            if (metric.batchProgressObservations > metric.rawSampleLimit) {
                append(
                        combined,
                        metric.batchProgressSteadyStateState,
                        metric.batchProgressSteadyStateAvgServiceTime,
                        null,
                        count);
            }
        }
        return new BatchProgressStatistics(
                total,
                head.progressScalars(),
                steady.progressScalars(),
                combined.progressScalars(),
                head.correlations(),
                steady.correlations(),
                combined.correlations());
    }

    private static BatchCompleteStatistics calculateBatchComplete(List<HighSpeedMetrics> metrics) {
        long total = metrics.stream()
                .mapToLong(value -> value.batchCompleteObservations)
                .sum();
        if (total == 0L) {
            return BatchCompleteStatistics.EMPTY;
        }
        Samples head = new Samples(true);
        Samples steady = new Samples(true);
        Samples combined = new Samples(true);
        for (HighSpeedMetrics metric : metrics) {
            int count = (int) Math.min(metric.batchCompleteObservations, metric.rawSampleLimit);
            append(
                    head,
                    metric.batchCompleteHeadState,
                    metric.batchCompleteHeadAvgServiceTime,
                    metric.batchCompleteHeadThroughput,
                    count);
            append(
                    steady,
                    metric.batchCompleteSteadyStateState,
                    metric.batchCompleteSteadyStateAvgServiceTime,
                    metric.batchCompleteSteadyStateThroughput,
                    count);
            append(
                    combined,
                    metric.batchCompleteHeadState,
                    metric.batchCompleteHeadAvgServiceTime,
                    metric.batchCompleteHeadThroughput,
                    count);
            if (metric.batchCompleteObservations > metric.rawSampleLimit) {
                append(
                        combined,
                        metric.batchCompleteSteadyStateState,
                        metric.batchCompleteSteadyStateAvgServiceTime,
                        metric.batchCompleteSteadyStateThroughput,
                        count);
            }
        }
        return new BatchCompleteStatistics(
                total,
                head.completeScalars(),
                steady.completeScalars(),
                combined.completeScalars(),
                head.correlations(),
                steady.correlations(),
                combined.correlations());
    }

    private static void append(
            Samples destination, long[][] states, double[] serviceTimes, @Nullable double[] throughputs, int count) {
        for (int index = 0; index < count; index++) {
            long[] state = states[index];
            double workers = state[3];
            double productive = state[6];
            destination.rows.add(new double[] {
                state[2],
                workers,
                productive,
                workers > 0.0 ? productive / workers : 0.0,
                state[4],
                state[5],
                serviceTimes[index],
                throughputs == null ? Double.NaN : throughputs[index]
            });
        }
    }

    private static final class Samples {
        private final boolean includesThroughput;
        private final List<double[]> rows = new ArrayList<>();

        private Samples(boolean includesThroughput) {
            this.includesThroughput = includesThroughput;
        }

        private BatchProgressScalars progressScalars() {
            return new BatchProgressScalars(
                    summary(UPSTREAM),
                    summary(WORKERS),
                    summary(PRODUCTIVE),
                    summary(PRODUCTIVE_RATIO),
                    summary(RANK),
                    summary(CONTENTION),
                    summary(SERVICE_TIME));
        }

        private BatchCompleteScalars completeScalars() {
            return new BatchCompleteScalars(
                    summary(UPSTREAM),
                    summary(WORKERS),
                    summary(PRODUCTIVE),
                    summary(PRODUCTIVE_RATIO),
                    summary(RANK),
                    summary(CONTENTION),
                    summary(SERVICE_TIME),
                    summary(THROUGHPUT));
        }

        private ScalarSummary summary(int column) {
            double[] values = new double[this.rows.size()];
            for (int index = 0; index < values.length; index++) {
                values[index] = this.rows.get(index)[column];
            }
            return ScalarSummary.of(values);
        }

        private CorrelationResult correlations() {
            String[] columns = this.includesThroughput
                    ? BatchCompleteStatistics.COLUMN_NAMES
                    : BatchProgressStatistics.COLUMN_NAMES;
            if (this.rows.size() < 2) {
                return CorrelationResult.empty(columns);
            }
            int[] selected = this.includesThroughput
                    ? new int[] {CONTENTION, PRODUCTIVE_RATIO, SERVICE_TIME, THROUGHPUT}
                    : new int[] {CONTENTION, PRODUCTIVE_RATIO, SERVICE_TIME};
            double[][] data = new double[this.rows.size()][selected.length];
            for (int row = 0; row < data.length; row++) {
                for (int column = 0; column < selected.length; column++) {
                    data[row][column] = this.rows.get(row)[selected[column]];
                }
            }
            return CorrelationResult.of(columns, data);
        }
    }
}
