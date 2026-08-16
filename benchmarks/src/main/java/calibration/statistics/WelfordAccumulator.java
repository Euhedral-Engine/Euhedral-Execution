package calibration.statistics;

/// Mutable Welford accumulator for single-pass mean and variance calculation.
public final class WelfordAccumulator {
    private long count;
    private double mean;
    private double m2;

    public WelfordAccumulator() {
        reset();
    }

    /// Records a single finite sample value.
    public void record(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Input value must be finite, got: " + value);
        }
        if (count == 0L) {
            count = 1L;
            mean = value;
            m2 = 0.0;
            return;
        }
        count++;
        double delta = value - mean;
        mean += delta / count;
        double delta2 = value - mean;
        m2 += delta * delta2;
    }

    /// Records all values in the given array.
    public void recordAll(double... values) {
        if (values != null) {
            for (double v : values) {
                record(v);
            }
        }
    }

    /// Resets the accumulator state.
    public void reset() {
        count = 0L;
        mean = Double.NaN;
        m2 = Double.NaN;
    }

    public long count() {
        return count;
    }

    public double mean() {
        return count > 0L ? mean : Double.NaN;
    }

    public double m2() {
        return count > 0L ? m2 : Double.NaN;
    }

    public double sampleVariance() {
        return count >= 2L ? (m2 / (count - 1L)) : Double.NaN;
    }

    public double standardDeviation() {
        double variance = sampleVariance();
        return Double.isNaN(variance) ? Double.NaN : Math.sqrt(variance);
    }
}
