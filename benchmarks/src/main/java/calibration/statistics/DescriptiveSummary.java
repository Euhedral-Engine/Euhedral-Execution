package calibration.statistics;

import java.util.Collection;
import java.util.Objects;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.jspecify.annotations.NonNull;

/// Descriptive trial statistics for a sequence of measured values.
public record DescriptiveSummary(
        long count,
        double mean,
        double standardDeviation,
        double variance,
        double coefficientOfVariation,
        double min,
        double max,
        double median) {

    public static final DescriptiveSummary EMPTY = new DescriptiveSummary(
            0L, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    public static DescriptiveSummary empty() {
        return EMPTY;
    }

    public static DescriptiveSummary of(double... values) {
        if (values == null || values.length == 0) {
            return EMPTY;
        }
        for (double v : values) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("Input values must be finite, got: " + v);
            }
        }
        if (values.length == 1) {
            double v = values[0];
            return new DescriptiveSummary(1L, v, Double.NaN, Double.NaN, Double.NaN, v, v, v);
        }

        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (double v : values) {
            stats.addValue(v);
        }

        long count = stats.getN();
        double mean = stats.getMean();
        double variance = stats.getVariance();
        double standardDeviation = stats.getStandardDeviation();
        double cv = (mean != 0.0 && Double.isFinite(mean) && Double.isFinite(standardDeviation))
                ? (standardDeviation / mean)
                : Double.NaN;
        double min = stats.getMin();
        double max = stats.getMax();
        double median = stats.getPercentile(50.0);

        return new DescriptiveSummary(count, mean, standardDeviation, variance, cv, min, max, median);
    }

    public static DescriptiveSummary of(@NonNull Collection<Double> values) {
        Objects.requireNonNull(values, "values must not be null");
        if (values.isEmpty()) {
            return EMPTY;
        }
        double[] array = new double[values.size()];
        int idx = 0;
        for (Double v : values) {
            Objects.requireNonNull(v, "value element must not be null");
            array[idx++] = v;
        }
        return of(array);
    }

    /// Calculates relative throughput change percentage: 100 * (candidate - baseline) / baseline.
    public static double relativeThroughputChange(double baseline, double candidate) {
        if (!Double.isFinite(baseline) || !Double.isFinite(candidate) || baseline == 0.0) {
            return Double.NaN;
        }
        return 100.0 * (candidate - baseline) / baseline;
    }
}
