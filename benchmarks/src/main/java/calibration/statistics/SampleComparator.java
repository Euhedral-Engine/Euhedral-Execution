package calibration.statistics;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Variance-aware comparison for two completed Welford samples.
public final class SampleComparator {

    private SampleComparator() {}

    /// Compares two completed Welford accumulators A and B.
    public static SampleComparison compare(@NonNull WelfordAccumulator a, @NonNull WelfordAccumulator b) {
        Objects.requireNonNull(a, "Accumulator A must not be null");
        Objects.requireNonNull(b, "Accumulator B must not be null");
        return compare(a.mean(), a.sampleVariance(), a.count(), b.mean(), b.sampleVariance(), b.count());
    }

    /// Compares two samples with given mean, sample variance, and count.
    public static SampleComparison compare(
            double meanA, double varianceA, long countA, double meanB, double varianceB, long countB) {

        if (countA < 2L
                || countB < 2L
                || !Double.isFinite(meanA)
                || !Double.isFinite(meanB)
                || !Double.isFinite(varianceA)
                || !Double.isFinite(varianceB)
                || varianceA < 0.0
                || varianceB < 0.0) {
            double delta = (Double.isFinite(meanA) && Double.isFinite(meanB)) ? (meanB - meanA) : Double.NaN;
            return new SampleComparison(ComparisonOutcome.INCONCLUSIVE, delta, Double.NaN, Double.NaN, Double.NaN);
        }

        double delta = meanB - meanA;
        double uncertainty = 2.0 * Math.sqrt((varianceA / countA) + (varianceB / countB));
        double practical = 0.01 * Math.max(meanA, meanB);
        double margin = Math.max(uncertainty, practical);

        ComparisonOutcome outcome;
        if (delta > margin) {
            outcome = ComparisonOutcome.B_BETTER;
        } else if (delta < -margin) {
            outcome = ComparisonOutcome.A_BETTER;
        } else if (uncertainty <= practical && Math.abs(delta) <= practical) {
            outcome = ComparisonOutcome.EQUIVALENT;
        } else {
            outcome = ComparisonOutcome.INCONCLUSIVE;
        }

        return new SampleComparison(outcome, delta, uncertainty, practical, margin);
    }
}
