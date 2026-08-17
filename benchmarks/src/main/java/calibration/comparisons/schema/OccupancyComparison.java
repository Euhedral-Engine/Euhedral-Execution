package calibration.comparisons.schema;

import calibration.statistics.Band;
import calibration.statistics.iteration.BranchOccupancyResult;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Comparison between matching baseline and candidate 5x5 branch occupancy surfaces.
public record OccupancyComparison(
        @NonNull BranchOccupancyResult baseline,
        @NonNull BranchOccupancyResult candidate,
        long[][] countDeltas,
        double[][] probabilityDeltas,
        double contentionCentroidDelta,
        double bodyCentroidDelta,
        double centroidDistance,
        double contentionVarianceDelta,
        double bodyVarianceDelta,
        double covarianceDelta,
        double radiusDelta,
        double totalVariationDistance) {

    public OccupancyComparison {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        if (countDeltas != null) {
            long[][] copy = new long[countDeltas.length][];
            for (int i = 0; i < countDeltas.length; i++) {
                if (countDeltas[i] != null) {
                    copy[i] = countDeltas[i].clone();
                }
            }
            countDeltas = copy;
        } else {
            countDeltas = new long[Band.GRID_SIZE][Band.GRID_SIZE];
        }

        if (probabilityDeltas != null) {
            double[][] copy = new double[probabilityDeltas.length][];
            for (int i = 0; i < probabilityDeltas.length; i++) {
                if (probabilityDeltas[i] != null) {
                    copy[i] = probabilityDeltas[i].clone();
                }
            }
            probabilityDeltas = copy;
        } else {
            probabilityDeltas = new double[Band.GRID_SIZE][Band.GRID_SIZE];
        }
    }

    @Override
    public long[][] countDeltas() {
        long[][] copy = new long[countDeltas.length][];
        for (int i = 0; i < countDeltas.length; i++) {
            copy[i] = countDeltas[i].clone();
        }
        return copy;
    }

    @Override
    public double[][] probabilityDeltas() {
        double[][] copy = new double[probabilityDeltas.length][];
        for (int i = 0; i < probabilityDeltas.length; i++) {
            copy[i] = probabilityDeltas[i].clone();
        }
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OccupancyComparison that)) return false;
        return Double.compare(that.contentionCentroidDelta, contentionCentroidDelta) == 0
                && Double.compare(that.bodyCentroidDelta, bodyCentroidDelta) == 0
                && Double.compare(that.centroidDistance, centroidDistance) == 0
                && Double.compare(that.contentionVarianceDelta, contentionVarianceDelta) == 0
                && Double.compare(that.bodyVarianceDelta, bodyVarianceDelta) == 0
                && Double.compare(that.covarianceDelta, covarianceDelta) == 0
                && Double.compare(that.radiusDelta, radiusDelta) == 0
                && Double.compare(that.totalVariationDistance, totalVariationDistance) == 0
                && baseline.equals(that.baseline)
                && candidate.equals(that.candidate)
                && Arrays.deepEquals(countDeltas, that.countDeltas)
                && Arrays.deepEquals(probabilityDeltas, that.probabilityDeltas);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                baseline,
                candidate,
                contentionCentroidDelta,
                bodyCentroidDelta,
                centroidDistance,
                contentionVarianceDelta,
                bodyVarianceDelta,
                covarianceDelta,
                radiusDelta,
                totalVariationDistance);
        result = 31 * result + Arrays.deepHashCode(countDeltas);
        result = 31 * result + Arrays.deepHashCode(probabilityDeltas);
        return result;
    }
}
