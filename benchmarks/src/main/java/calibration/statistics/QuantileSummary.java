package calibration.statistics;

import java.util.Collection;
import java.util.Objects;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.jspecify.annotations.NonNull;

/// Quantile and dispersion statistics for a raw numeric sample.
public record QuantileSummary(
        double p25, double p50, double p75, double p95, double iqr, double normalizedIqr, double p95ToP50Ratio) {

    public static final QuantileSummary EMPTY =
            new QuantileSummary(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    public static QuantileSummary empty() {
        return EMPTY;
    }

    public static QuantileSummary of(double... values) {
        if (values == null || values.length == 0) {
            return EMPTY;
        }
        for (double v : values) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("Input values must be finite, got: " + v);
            }
        }

        Percentile percentile = new Percentile();
        percentile.setData(values);

        double p25 = percentile.evaluate(25.0);
        double p50 = percentile.evaluate(50.0);
        double p75 = percentile.evaluate(75.0);
        double p95 = percentile.evaluate(95.0);

        double iqr = p75 - p25;
        double normalizedIqr = (p50 != 0.0 && Double.isFinite(p50)) ? (iqr / p50) : Double.NaN;
        double p95ToP50Ratio = (p50 != 0.0 && Double.isFinite(p50)) ? (p95 / p50) : Double.NaN;

        return new QuantileSummary(p25, p50, p75, p95, iqr, normalizedIqr, p95ToP50Ratio);
    }

    public static QuantileSummary of(@NonNull Collection<Double> values) {
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
}
