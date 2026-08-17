package calibration.comparisons;

import calibration.comparisons.schema.ComparisonCompatibility;
import calibration.comparisons.schema.CompatibilityStatus;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.PerformanceComparison;
import calibration.comparisons.schema.ThroughputResult;
import calibration.statistics.ComparisonOutcome;
import calibration.statistics.SampleComparator;
import calibration.statistics.SampleComparison;
import calibration.statistics.iteration.ScalarSummary;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Calculates authoritative throughput comparison results from completed calibration runs.
public final class PerformanceComparisonCalculator {

    private PerformanceComparisonCalculator() {}

    /// Compares throughput between baseline and candidate runs, performing compatibility analysis first.
    public static @Nullable PerformanceComparison compare(
            @NonNull CompletedRun baseline, @NonNull CompletedRun candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        ComparisonCompatibility compatibility = ComparisonCompatibilityAnalyzer.analyze(baseline, candidate);
        return compare(baseline, candidate, compatibility);
    }

    /// Compares throughput between baseline and candidate runs under the given compatibility verdict.
    public static @Nullable PerformanceComparison compare(
            @NonNull CompletedRun baseline,
            @NonNull CompletedRun candidate,
            @NonNull ComparisonCompatibility compatibility) {

        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(compatibility, "compatibility must not be null");

        if (!compatibility.isComparable() || compatibility.status() == CompatibilityStatus.INCOMPATIBLE) {
            return null;
        }

        ThroughputResult baseThroughput = baseline.throughput();
        ThroughputResult candThroughput = candidate.throughput();

        String baseUnit = baseThroughput.scoreUnit();
        String candUnit = candThroughput.scoreUnit();
        if (!baseUnit.equals(candUnit)) {
            throw new IllegalArgumentException("Throughput unit mismatch: baseline unit '" + baseUnit
                    + "' does not match candidate unit '" + candUnit + "'");
        }

        List<Double> baseForkScores = extractForkScores(baseThroughput);
        List<Double> candForkScores = extractForkScores(candThroughput);

        ScalarSummary baselineForkSummary = ScalarSummary.of(baseForkScores);
        ScalarSummary candidateForkSummary = ScalarSummary.of(candForkScores);

        double baselineMean = baselineForkSummary.mean();
        double candidateMean = candidateForkSummary.mean();

        if (!Double.isFinite(baselineMean) || baselineMean <= 0.0) {
            throw new IllegalArgumentException(
                    "Baseline throughput mean must be finite and positive, got: " + baselineMean);
        }
        if (!Double.isFinite(candidateMean) || candidateMean <= 0.0) {
            throw new IllegalArgumentException(
                    "Candidate throughput mean must be finite and positive, got: " + candidateMean);
        }

        double absoluteDelta = candidateMean - baselineMean;
        double relativeDeltaPercent = 100.0 * absoluteDelta / baselineMean;

        SampleComparison sampleComparison = SampleComparator.compare(
                baselineMean,
                baselineForkSummary.variance(),
                baselineForkSummary.count(),
                candidateMean,
                candidateForkSummary.variance(),
                candidateForkSummary.count());

        ComparisonOutcome outcome = sampleComparison.outcome();

        return new PerformanceComparison(
                baseThroughput,
                candThroughput,
                absoluteDelta,
                relativeDeltaPercent,
                baselineForkSummary,
                candidateForkSummary,
                outcome);
    }

    private static List<Double> extractForkScores(ThroughputResult throughput) {
        List<Double> rawScores = throughput.forkScores();
        List<Double> scores;
        if (rawScores != null && !rawScores.isEmpty()) {
            scores = rawScores;
        } else {
            double score = throughput.score();
            if (!Double.isFinite(score) || score <= 0.0) {
                throw new IllegalArgumentException("Throughput score must be finite and positive: " + score);
            }
            scores = List.of(score);
        }

        for (Double s : scores) {
            if (s == null || !Double.isFinite(s) || s <= 0.0) {
                throw new IllegalArgumentException("Fork throughput score must be finite and positive, got: " + s);
            }
        }
        return scores;
    }
}
