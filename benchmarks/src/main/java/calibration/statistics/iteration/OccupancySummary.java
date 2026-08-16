package calibration.statistics.iteration;

import calibration.statistics.Band;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/// Fixed 5x5 occupancy summary for branch counts.
public record OccupancySummary(
        long totalCount,
        double[][] probabilities,
        double contentionCentroid,
        double bodyCentroid,
        double contentionVariance,
        double bodyVariance,
        double contentionBodyCovariance,
        double radiusSquared,
        double radius) {

    public static final OccupancySummary EMPTY = new OccupancySummary(
            0L,
            new double[Band.GRID_SIZE][Band.GRID_SIZE],
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN);

    public OccupancySummary {
        if (probabilities != null) {
            double[][] copy = new double[probabilities.length][];
            for (int i = 0; i < probabilities.length; i++) {
                if (probabilities[i] != null) {
                    copy[i] = probabilities[i].clone();
                }
            }
            probabilities = copy;
        }
    }

    @Override
    public double[][] probabilities() {
        if (probabilities == null) {
            return new double[Band.GRID_SIZE][Band.GRID_SIZE];
        }
        double[][] copy = new double[probabilities.length][];
        for (int i = 0; i < probabilities.length; i++) {
            copy[i] = probabilities[i].clone();
        }
        return copy;
    }

    public boolean isEmpty() {
        return totalCount == 0L;
    }

    /// Computes Euclidean distance between this occupancy centroid and another.
    public double distanceTo(@Nullable OccupancySummary other) {
        return distance(this, other);
    }

    /// Computes Euclidean distance between two occupancy centroids.
    public static double distance(@Nullable OccupancySummary a, @Nullable OccupancySummary b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return Double.NaN;
        }
        return distance(a.contentionCentroid(), a.bodyCentroid(), b.contentionCentroid(), b.bodyCentroid());
    }

    /// Computes Euclidean distance between two centroid coordinates: sqrt((contentionA - contentionB)^2 + (bodyA -
    /// bodyB)^2).
    public static double distance(double contentionA, double bodyA, double contentionB, double bodyB) {
        if (!Double.isFinite(contentionA)
                || !Double.isFinite(bodyA)
                || !Double.isFinite(contentionB)
                || !Double.isFinite(bodyB)) {
            return Double.NaN;
        }
        return Math.hypot(contentionA - contentionB, bodyA - bodyB);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OccupancySummary that)) return false;
        return totalCount == that.totalCount
                && Double.compare(that.contentionCentroid, contentionCentroid) == 0
                && Double.compare(that.bodyCentroid, bodyCentroid) == 0
                && Double.compare(that.contentionVariance, contentionVariance) == 0
                && Double.compare(that.bodyVariance, bodyVariance) == 0
                && Double.compare(that.contentionBodyCovariance, contentionBodyCovariance) == 0
                && Double.compare(that.radiusSquared, radiusSquared) == 0
                && Double.compare(that.radius, radius) == 0
                && Arrays.deepEquals(probabilities, that.probabilities);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                totalCount,
                contentionCentroid,
                bodyCentroid,
                contentionVariance,
                bodyVariance,
                contentionBodyCovariance,
                radiusSquared,
                radius);
        result = 31 * result + Arrays.deepHashCode(probabilities);
        return result;
    }

    public String toTsvRow() {
        return contentionCentroid + "\t"
                + bodyCentroid + "\t"
                + contentionVariance + "\t"
                + bodyVariance + "\t"
                + contentionBodyCovariance + "\t"
                + radiusSquared + "\t"
                + radius;
    }
}
