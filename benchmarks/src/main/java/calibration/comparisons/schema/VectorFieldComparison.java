package calibration.comparisons.schema;

import calibration.statistics.DecisionGrid;
import calibration.statistics.VectorField;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Comparison between matching baseline and candidate 2x5 vector displacement fields.
public record VectorFieldComparison(
        @NonNull VectorField baseline, @NonNull VectorField candidate, VectorCellComparison[][] cells) {

    public VectorFieldComparison {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        if (cells != null) {
            VectorCellComparison[][] copy = new VectorCellComparison[cells.length][];
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] != null) {
                    copy[i] = cells[i].clone();
                }
            }
            cells = copy;
        } else {
            cells = new VectorCellComparison[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        }
    }

    @Override
    public VectorCellComparison[][] cells() {
        VectorCellComparison[][] copy = new VectorCellComparison[cells.length][];
        for (int i = 0; i < cells.length; i++) {
            copy[i] = cells[i].clone();
        }
        return copy;
    }

    public VectorCellComparison cell(int contentionBand, int bodyBand) {
        if (contentionBand < 0
                || contentionBand >= DecisionGrid.CONTENTION_OUTCOMES
                || bodyBand < 0
                || bodyBand >= DecisionGrid.BODY_OUTCOMES) {
            throw new IllegalArgumentException("Coordinates out of bounds: (" + contentionBand + ", " + bodyBand + ")");
        }
        return cells[contentionBand][bodyBand];
    }

    public VectorCellComparison cell(int stateIndex) {
        if (stateIndex < 0 || stateIndex >= DecisionGrid.TOTAL_STATES) {
            throw new IllegalArgumentException("State index out of bounds: " + stateIndex);
        }
        return cells[stateIndex / DecisionGrid.BODY_OUTCOMES][stateIndex % DecisionGrid.BODY_OUTCOMES];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VectorFieldComparison that)) return false;
        return baseline.equals(that.baseline)
                && candidate.equals(that.candidate)
                && Arrays.deepEquals(cells, that.cells);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(baseline, candidate) + Arrays.deepHashCode(cells);
    }
}
