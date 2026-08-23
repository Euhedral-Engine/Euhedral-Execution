package calibration.statistics;

import calibration.statistics.iteration.TransitionAnalysis;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// 2x5 local vector field showing average next-state displacement for every source cell.
public record VectorField(VectorCell[][] grid) {

    public VectorField {
        if (grid != null) {
            VectorCell[][] copy = new VectorCell[grid.length][];
            for (int i = 0; i < grid.length; i++) {
                if (grid[i] != null) {
                    copy[i] = grid[i].clone();
                }
            }
            grid = copy;
        }
    }

    @Override
    public VectorCell[][] grid() {
        if (grid == null) {
            return new VectorCell[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        }
        VectorCell[][] copy = new VectorCell[grid.length][];
        for (int i = 0; i < grid.length; i++) {
            copy[i] = grid[i].clone();
        }
        return copy;
    }

    public VectorCell cell(int contentionBand, int bodyBand) {
        if (contentionBand < 0
                || contentionBand >= DecisionGrid.CONTENTION_OUTCOMES
                || bodyBand < 0
                || bodyBand >= DecisionGrid.BODY_OUTCOMES) {
            throw new IllegalArgumentException("Coordinates out of bounds: (" + contentionBand + ", " + bodyBand + ")");
        }
        return grid[contentionBand][bodyBand];
    }

    public VectorCell cell(int stateIndex) {
        if (stateIndex < 0 || stateIndex >= DecisionGrid.TOTAL_STATES) {
            throw new IllegalArgumentException("State index out of bounds: " + stateIndex);
        }
        return grid[stateIndex / DecisionGrid.BODY_OUTCOMES][stateIndex % DecisionGrid.BODY_OUTCOMES];
    }

    public static VectorField compute(int[] stateSequence) {
        TransitionAnalysis analysis = TransitionAnalysis.compute(stateSequence);
        return compute(analysis.transitionCounts());
    }

    public static VectorField compute(@NonNull List<Integer> stateSequence) {
        Objects.requireNonNull(stateSequence, "stateSequence must not be null");
        TransitionAnalysis analysis = TransitionAnalysis.compute(stateSequence);
        return compute(analysis.transitionCounts());
    }

    public static VectorField compute(long[][] transitionCounts) {
        if (transitionCounts == null || transitionCounts.length != DecisionGrid.TOTAL_STATES) {
            throw new IllegalArgumentException(
                    "transitionCounts must be " + DecisionGrid.TOTAL_STATES + "x" + DecisionGrid.TOTAL_STATES);
        }

        VectorCell[][] grid = new VectorCell[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];

        for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
            for (int j = 0; j < DecisionGrid.BODY_OUTCOMES; j++) {
                int sourceState = i * DecisionGrid.BODY_OUTCOMES + j;
                long count = 0L;
                double sumDeltaContention = 0.0;
                double sumDeltaBody = 0.0;

                for (int nextState = 0; nextState < DecisionGrid.TOTAL_STATES; nextState++) {
                    long transitionCount = transitionCounts[sourceState][nextState];
                    if (transitionCount > 0L) {
                        int nextI = nextState / DecisionGrid.BODY_OUTCOMES;
                        int nextJ = nextState % DecisionGrid.BODY_OUTCOMES;
                        int deltaI = nextI - i;
                        int deltaJ = nextJ - j;

                        sumDeltaContention += deltaI * transitionCount;
                        sumDeltaBody += deltaJ * transitionCount;
                        count += transitionCount;
                    }
                }

                if (count > 0L) {
                    double meanDeltaContention = sumDeltaContention / (double) count;
                    double meanDeltaBody = sumDeltaBody / (double) count;
                    double magnitude = Math.hypot(meanDeltaContention, meanDeltaBody);
                    grid[i][j] = new VectorCell(i, j, count, meanDeltaContention, meanDeltaBody, magnitude);
                } else {
                    grid[i][j] = VectorCell.empty(i, j);
                }
            }
        }

        return new VectorField(grid);
    }

    public boolean isEmpty() {
        if (grid == null) {
            return true;
        }
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] != null) {
                for (int j = 0; j < grid[i].length; j++) {
                    if (grid[i][j] != null && grid[i][j].transitionCount() > 0L) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
