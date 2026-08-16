package calibration.statistics.iteration;

import java.util.Arrays;
import java.util.Objects;

import calibration.statistics.Band;
import org.jspecify.annotations.NonNull;

/// Direct analysis and accumulation for 5x5 occupancy mesh.
public final class OccupancyMesh {

    private final long[][] counts = new long[Band.GRID_SIZE][Band.GRID_SIZE];

    public OccupancyMesh() {}

    public void record(int contentionBand, int bodyBand) {
        record(contentionBand, bodyBand, 1L);
    }

    public void record(int contentionBand, int bodyBand, long count) {
        validateIndex(contentionBand, "contentionBand");
        validateIndex(bodyBand, "bodyBand");
        if (count < 0L) {
            throw new IllegalArgumentException("count must be non-negative: " + count);
        }
        counts[contentionBand][bodyBand] += count;
    }

    public void record(@NonNull Band contentionBand, @NonNull Band bodyBand) {
        Objects.requireNonNull(contentionBand, "contentionBand must not be null");
        Objects.requireNonNull(bodyBand, "bodyBand must not be null");
        record(contentionBand.index(), bodyBand.index(), 1L);
    }

    public void record(@NonNull Band contentionBand, @NonNull Band bodyBand, long count) {
        Objects.requireNonNull(contentionBand, "contentionBand must not be null");
        Objects.requireNonNull(bodyBand, "bodyBand must not be null");
        record(contentionBand.index(), bodyBand.index(), count);
    }

    public void reset() {
        for (int i = 0; i < Band.GRID_SIZE; i++) {
            Arrays.fill(counts[i], 0L);
        }
    }

    public OccupancySummary summarize() {
        return analyze(counts);
    }

    public long countAt(int contentionBand, int bodyBand) {
        validateIndex(contentionBand, "contentionBand");
        validateIndex(bodyBand, "bodyBand");
        return counts[contentionBand][bodyBand];
    }

    public long[][] counts() {
        long[][] copy = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        for (int i = 0; i < Band.GRID_SIZE; i++) {
            System.arraycopy(counts[i], 0, copy[i], 0, Band.GRID_SIZE);
        }
        return copy;
    }

    /// Analyzes a 5x5 count matrix directly without expanding into repeated samples.
    public static OccupancySummary analyze(long[][] counts) {
        if (counts == null) {
            throw new NullPointerException("counts must not be null");
        }
        if (counts.length != Band.GRID_SIZE) {
            throw new IllegalArgumentException(
                    "counts array must have " + Band.GRID_SIZE + " rows, got: " + counts.length);
        }
        long totalCount = 0L;
        for (int i = 0; i < Band.GRID_SIZE; i++) {
            if (counts[i] == null || counts[i].length != Band.GRID_SIZE) {
                throw new IllegalArgumentException("Row " + i + " must have length " + Band.GRID_SIZE);
            }
            for (int j = 0; j < Band.GRID_SIZE; j++) {
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

        double[][] probabilities = new double[Band.GRID_SIZE][Band.GRID_SIZE];
        double contentionCentroid = 0.0;
        double bodyCentroid = 0.0;

        for (int i = 0; i < Band.GRID_SIZE; i++) {
            for (int j = 0; j < Band.GRID_SIZE; j++) {
                double p = (double) counts[i][j] / (double) totalCount;
                probabilities[i][j] = p;
                contentionCentroid += i * p;
                bodyCentroid += j * p;
            }
        }

        double contentionVariance = 0.0;
        double bodyVariance = 0.0;
        double contentionBodyCovariance = 0.0;

        for (int i = 0; i < Band.GRID_SIZE; i++) {
            for (int j = 0; j < Band.GRID_SIZE; j++) {
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

    private static void validateIndex(int index, String name) {
        if (index < 0 || index >= Band.GRID_SIZE) {
            throw new IllegalArgumentException(
                    name + " out of bounds: " + index + " (expected 0.." + (Band.GRID_SIZE - 1) + ")");
        }
    }
}
