package calibration.statistics;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// 5x5 local vector field showing average next-state displacement for every source cell.
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
            return new VectorCell[Band.GRID_SIZE][Band.GRID_SIZE];
        }
        VectorCell[][] copy = new VectorCell[grid.length][];
        for (int i = 0; i < grid.length; i++) {
            copy[i] = grid[i].clone();
        }
        return copy;
    }

    public VectorCell cell(int contentionBand, int bodyBand) {
        if (contentionBand < 0 || contentionBand >= Band.GRID_SIZE || bodyBand < 0 || bodyBand >= Band.GRID_SIZE) {
            throw new IllegalArgumentException("Coordinates out of bounds: (" + contentionBand + ", " + bodyBand + ")");
        }
        return grid[contentionBand][bodyBand];
    }

    public VectorCell cell(int stateIndex) {
        if (stateIndex < 0 || stateIndex >= Band.TOTAL_STATES) {
            throw new IllegalArgumentException("State index out of bounds: " + stateIndex);
        }
        return grid[stateIndex / Band.GRID_SIZE][stateIndex % Band.GRID_SIZE];
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
        if (transitionCounts == null || transitionCounts.length != Band.TOTAL_STATES) {
            throw new IllegalArgumentException(
                    "transitionCounts must be " + Band.TOTAL_STATES + "x" + Band.TOTAL_STATES);
        }

        VectorCell[][] grid = new VectorCell[Band.GRID_SIZE][Band.GRID_SIZE];

        for (int i = 0; i < Band.GRID_SIZE; i++) {
            for (int j = 0; j < Band.GRID_SIZE; j++) {
                int sourceState = i * Band.GRID_SIZE + j;
                long count = 0L;
                double sumDeltaContention = 0.0;
                double sumDeltaBody = 0.0;

                for (int nextState = 0; nextState < Band.TOTAL_STATES; nextState++) {
                    long transitionCount = transitionCounts[sourceState][nextState];
                    if (transitionCount > 0L) {
                        int nextI = nextState / Band.GRID_SIZE;
                        int nextJ = nextState % Band.GRID_SIZE;
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
}
