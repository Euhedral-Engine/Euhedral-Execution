package calibration.statistics.iteration;

import calibration.statistics.DescriptiveSummary;
import calibration.statistics.QuantileSummary;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Combined descriptive and quantile statistics for a continuous scalar series.
public record ScalarSummary(
        @NonNull DescriptiveSummary descriptive, @NonNull QuantileSummary quantiles) {

    public static final ScalarSummary EMPTY = new ScalarSummary(DescriptiveSummary.EMPTY, QuantileSummary.EMPTY);

    public ScalarSummary {
        Objects.requireNonNull(descriptive, "descriptive must not be null");
        Objects.requireNonNull(quantiles, "quantiles must not be null");
    }

    public static ScalarSummary empty() {
        return EMPTY;
    }

    public static ScalarSummary of(double @Nullable ... values) {
        if (values == null || values.length == 0) {
            return EMPTY;
        }
        return new ScalarSummary(DescriptiveSummary.of(values), QuantileSummary.of(values));
    }

    public static ScalarSummary of(long @Nullable ... values) {
        if (values == null || values.length == 0) {
            return EMPTY;
        }
        double[] doubleValues = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            doubleValues[i] = (double) values[i];
        }
        return of(doubleValues);
    }

    public boolean isEmpty() {
        return descriptive.count() == 0L;
    }

    public long count() {
        return descriptive.count();
    }

    public double mean() {
        return descriptive.mean();
    }

    public double variance() {
        return descriptive.variance();
    }

    public double standardDeviation() {
        return descriptive.standardDeviation();
    }

    public double coefficientOfVariation() {
        return descriptive.coefficientOfVariation();
    }

    public double min() {
        return descriptive.min();
    }

    public double max() {
        return descriptive.max();
    }

    public double median() {
        return descriptive.median();
    }

    public double p25() {
        return quantiles.p25();
    }

    public double p50() {
        return quantiles.p50();
    }

    public double p75() {
        return quantiles.p75();
    }

    public double p95() {
        return quantiles.p95();
    }

    public double iqr() {
        return quantiles.iqr();
    }

    public double normalizedIqr() {
        return quantiles.normalizedIqr();
    }

    public double p95ToP50Ratio() {
        return quantiles.p95ToP50Ratio();
    }

    public String toTsvRow() {
        return count() + "\t"
                + mean() + "\t"
                + standardDeviation() + "\t"
                + variance() + "\t"
                + coefficientOfVariation() + "\t"
                + min() + "\t"
                + max() + "\t"
                + median() + "\t"
                + p25() + "\t"
                + p50() + "\t"
                + p75() + "\t"
                + p95() + "\t"
                + iqr() + "\t"
                + normalizedIqr() + "\t"
                + p95ToP50Ratio();
    }
}
