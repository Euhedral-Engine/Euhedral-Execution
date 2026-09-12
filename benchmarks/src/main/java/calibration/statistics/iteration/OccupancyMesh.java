package calibration.statistics.iteration;

import calibration.statistics.DecisionGrid;
import java.util.Arrays;

/// Direct analysis and accumulation for the 2x5 decision surface.
public final class OccupancyMesh {

    private final long[][] counts = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];

    public OccupancyMesh() {}

    public void record(int contentionBand, int bodyBand) {
        record(contentionBand, bodyBand, 1L);
    }

    public void record(int contentionBand, int bodyBand, long count) {
        validateContentionIndex(contentionBand);
        validateBodyIndex(bodyBand);
        if (count < 0L) {
            throw new IllegalArgumentException("count must be non-negative: " + count);
        }
        counts[contentionBand][bodyBand] += count;
    }

    public void reset() {
        for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
            Arrays.fill(counts[i], 0L);
        }
    }

    public OccupancySummary summarize() {
        return analyze(counts);
    }

    public long countAt(int contentionBand, int bodyBand) {
        validateContentionIndex(contentionBand);
        validateBodyIndex(bodyBand);
        return counts[contentionBand][bodyBand];
    }

    public long[][] counts() {
        long[][] copy = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
            System.arraycopy(counts[i], 0, copy[i], 0, DecisionGrid.BODY_OUTCOMES);
        }
        return copy;
    }

    /// Analyzes a 2x5 count matrix directly without expanding into repeated samples.
    public static OccupancySummary analyze(long[][] counts) {
        if (counts == null) {
            throw new NullPointerException("counts must not be null");
        }
        if (counts.length != DecisionGrid.CONTENTION_OUTCOMES) {
            throw new IllegalArgumentException(
                    "counts array must have " + DecisionGrid.CONTENTION_OUTCOMES + " rows, got: " + counts.length);
        }
        long totalCount = 0L;
        for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
            if (counts[i] == null || counts[i].length != DecisionGrid.BODY_OUTCOMES) {
                throw new IllegalArgumentException("Row " + i + " must have length " + DecisionGrid.BODY_OUTCOMES);
            }
            for (int j = 0; j < DecisionGrid.BODY_OUTCOMES; j++) {
                long c = counts[i][j];
                if (c < 0L) {
                    throw new IllegalArgumentException("Count at (" + i + ", " + j + ") must be non-negative: " + c);
                }
                totalCount += c;
            }
        }

        if (totalCount == 0L) {
            return OccupancySummary.EMPTY;
        }

        double[][] probabilities = new double[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        double contentionCentroid = 0.0;
        double bodyCentroid = 0.0;

        for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
            for (int j = 0; j < DecisionGrid.BODY_OUTCOMES; j++) {
                double p = (double) counts[i][j] / (double) totalCount;
                probabilities[i][j] = p;
                contentionCentroid += i * p;
                bodyCentroid += j * p;
            }
        }

        double contentionVariance = 0.0;
        double bodyVariance = 0.0;
        double contentionBodyCovariance = 0.0;

        for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
            for (int j = 0; j < DecisionGrid.BODY_OUTCOMES; j++) {
                double p = probabilities[i][j];
                double dContention = i - contentionCentroid;
                double dBody = j - bodyCentroid;

                contentionVariance += dContention * dContention * p;
                bodyVariance += dBody * dBody * p;
                contentionBodyCovariance += dContention * dBody * p;
            }
        }

        double radiusSquared = contentionVariance + bodyVariance;
        double radius = Math.sqrt(radiusSquared);

        return new OccupancySummary(
                totalCount,
                probabilities,
                contentionCentroid,
                bodyCentroid,
                contentionVariance,
                bodyVariance,
                contentionBodyCovariance,
                radiusSquared,
                radius);
    }

    private static void validateContentionIndex(int index) {
        if (index < 0 || index >= DecisionGrid.CONTENTION_OUTCOMES) {
            throw new IllegalArgumentException("contentionBand out of bounds: " + index + " (expected 0.."
                    + (DecisionGrid.CONTENTION_OUTCOMES - 1) + ")");
        }
    }

    private static void validateBodyIndex(int index) {
        if (index < 0 || index >= DecisionGrid.BODY_OUTCOMES) {
            throw new IllegalArgumentException(
                    "bodyBand out of bounds: " + index + " (expected 0.." + (DecisionGrid.BODY_OUTCOMES - 1) + ")");
        }
    }
}
