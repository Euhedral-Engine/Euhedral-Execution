package calibration.statistics.iteration;

import calibration.statistics.DecisionGrid;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Retains exact 2x5 branch counts alongside the derived occupancy summary.
public record BranchOccupancyResult(
        long[][] exactCounts, @NonNull OccupancySummary summary) {

    public static final BranchOccupancyResult EMPTY = new BranchOccupancyResult(
            new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES], OccupancySummary.EMPTY);

    public BranchOccupancyResult {
        Objects.requireNonNull(summary, "summary must not be null");
        if (exactCounts != null) {
            long[][] copy = new long[exactCounts.length][];
            for (int i = 0; i < exactCounts.length; i++) {
                if (exactCounts[i] != null) {
                    copy[i] = exactCounts[i].clone();
                }
            }
            exactCounts = copy;
        } else {
            exactCounts = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        }
    }

    public static BranchOccupancyResult empty() {
        return EMPTY;
    }

    public static BranchOccupancyResult of(long @Nullable [][] counts) {
        if (counts == null) {
            return EMPTY;
        }
        long[][] copy = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES && i < counts.length; i++) {
            if (counts[i] != null) {
                System.arraycopy(counts[i], 0, copy[i], 0, Math.min(counts[i].length, DecisionGrid.BODY_OUTCOMES));
            }
        }
        OccupancySummary summary = OccupancyMesh.analyze(copy);
        return new BranchOccupancyResult(copy, summary);
    }

    @Override
    public long[][] exactCounts() {
        long[][] copy = new long[exactCounts.length][];
        for (int i = 0; i < exactCounts.length; i++) {
            copy[i] = exactCounts[i].clone();
        }
        return copy;
    }

    public boolean isEmpty() {
        return summary.isEmpty();
    }

    public long totalCount() {
        return summary.totalCount();
    }

    public double[][] normalizedOccupancy() {
        return summary.probabilities();
    }

    public double contentionCentroid() {
        return summary.contentionCentroid();
    }

    public double bodyCentroid() {
        return summary.bodyCentroid();
    }

    public double contentionVariance() {
        return summary.contentionVariance();
    }

    public double bodyVariance() {
        return summary.bodyVariance();
    }

    public double contentionBodyCovariance() {
        return summary.contentionBodyCovariance();
    }

    public double radiusSquared() {
        return summary.radiusSquared();
    }

    public double radius() {
        return summary.radius();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BranchOccupancyResult that)) return false;
        return Arrays.deepEquals(exactCounts, that.exactCounts) && summary.equals(that.summary);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.deepHashCode(exactCounts) + summary.hashCode();
    }
}
