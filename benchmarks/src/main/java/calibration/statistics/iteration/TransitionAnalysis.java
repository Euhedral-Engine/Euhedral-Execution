package calibration.statistics.iteration;

import calibration.statistics.Band;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// 25-state transition matrix and oscillation analysis.
public final class TransitionAnalysis {

    private final long[][] transitionCounts;
    private final double[][] transitionProbabilities;
    private final double[] selfTransitionRates;
    private final int[] dominantOutgoingStates;
    private final double[] dominantOutgoingProbabilities;

    private TransitionAnalysis(
            long[][] transitionCounts,
            double[][] transitionProbabilities,
            double[] selfTransitionRates,
            int[] dominantOutgoingStates,
            double[] dominantOutgoingProbabilities) {
        this.transitionCounts = transitionCounts;
        this.transitionProbabilities = transitionProbabilities;
        this.selfTransitionRates = selfTransitionRates;
        this.dominantOutgoingStates = dominantOutgoingStates;
        this.dominantOutgoingProbabilities = dominantOutgoingProbabilities;
    }

    /// Converts (contentionBand, bodyBand) coordinates into a 0..24 state index.
    public static int toState(int contentionBand, int bodyBand) {
        if (contentionBand < 0 || contentionBand >= Band.GRID_SIZE) {
            throw new IllegalArgumentException("contentionBand out of bounds: " + contentionBand);
        }
        if (bodyBand < 0 || bodyBand >= Band.GRID_SIZE) {
            throw new IllegalArgumentException("bodyBand out of bounds: " + bodyBand);
        }
        return contentionBand * Band.GRID_SIZE + bodyBand;
    }

    /// Extracts contention band (0..4) from a 0..24 state index.
    public static int contentionBandOf(int state) {
        validateState(state);
        return state / Band.GRID_SIZE;
    }

    /// Extracts body band (0..4) from a 0..24 state index.
    public static int bodyBandOf(int state) {
        validateState(state);
        return state % Band.GRID_SIZE;
    }

    /// Computes transition analysis from an ordered sequence of state indices (0..24).
    public static TransitionAnalysis compute(int[] stateSequence) {
        long[][] counts = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        if (stateSequence != null && stateSequence.length >= 2) {
            for (int k = 0; k < stateSequence.length - 1; k++) {
                int from = stateSequence[k];
                int to = stateSequence[k + 1];
                validateState(from);
                validateState(to);
                counts[from][to]++;
            }
        }
        return computeFromCounts(counts);
    }

    /// Computes transition analysis from an ordered list of state indices (0..24).
    public static TransitionAnalysis compute(@NonNull List<Integer> stateSequence) {
        Objects.requireNonNull(stateSequence, "stateSequence must not be null");
        int[] array = new int[stateSequence.size()];
        for (int i = 0; i < stateSequence.size(); i++) {
            Integer s = stateSequence.get(i);
            Objects.requireNonNull(s, "State element must not be null");
            array[i] = s;
        }
        return compute(array);
    }

    /// Computes transition analysis from a raw 25x25 transition count matrix.
    public static TransitionAnalysis computeFromCounts(long[][] counts) {
        if (counts == null) {
            throw new NullPointerException("Counts matrix must not be null");
        }
        if (counts.length != Band.TOTAL_STATES) {
            throw new IllegalArgumentException("Counts matrix must be " + Band.TOTAL_STATES + "x" + Band.TOTAL_STATES);
        }

        long[][] countsCopy = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        double[][] probabilities = new double[Band.TOTAL_STATES][Band.TOTAL_STATES];
        double[] selfRates = new double[Band.TOTAL_STATES];
        int[] dominantStates = new int[Band.TOTAL_STATES];
        double[] dominantProbs = new double[Band.TOTAL_STATES];

        for (int a = 0; a < Band.TOTAL_STATES; a++) {
            if (counts[a] == null || counts[a].length != Band.TOTAL_STATES) {
                throw new IllegalArgumentException("Counts row " + a + " must be length " + Band.TOTAL_STATES);
            }
            long rowSum = 0L;
            for (int b = 0; b < Band.TOTAL_STATES; b++) {
                long c = counts[a][b];
                if (c < 0L) {
                    throw new IllegalArgumentException("Transition count cannot be negative: " + c);
                }
                countsCopy[a][b] = c;
                rowSum += c;
            }

            if (rowSum > 0L) {
                int dominantState = 0;
                long dominantCount = countsCopy[a][0];
                for (int b = 0; b < Band.TOTAL_STATES; b++) {
                    double p = (double) countsCopy[a][b] / (double) rowSum;
                    probabilities[a][b] = p;
                    if (countsCopy[a][b] > dominantCount) {
                        dominantCount = countsCopy[a][b];
                        dominantState = b;
                    }
                }
                selfRates[a] = probabilities[a][a];
                dominantStates[a] = dominantState;
                dominantProbs[a] = probabilities[a][dominantState];
            } else {
                dominantStates[a] = -1;
                dominantProbs[a] = 0.0;
                selfRates[a] = 0.0;
            }
        }

        return new TransitionAnalysis(countsCopy, probabilities, selfRates, dominantStates, dominantProbs);
    }

    public long[][] transitionCounts() {
        long[][] copy = new long[Band.TOTAL_STATES][Band.TOTAL_STATES];
        for (int i = 0; i < Band.TOTAL_STATES; i++) {
            System.arraycopy(transitionCounts[i], 0, copy[i], 0, Band.TOTAL_STATES);
        }
        return copy;
    }

    public double[][] transitionProbabilities() {
        double[][] copy = new double[Band.TOTAL_STATES][Band.TOTAL_STATES];
        for (int i = 0; i < Band.TOTAL_STATES; i++) {
            System.arraycopy(transitionProbabilities[i], 0, copy[i], 0, Band.TOTAL_STATES);
        }
        return copy;
    }

    public double selfTransitionRate(int state) {
        validateState(state);
        return selfTransitionRates[state];
    }

    public int dominantOutgoingState(int state) {
        validateState(state);
        return dominantOutgoingStates[state];
    }

    public double dominantOutgoingProbability(int state) {
        validateState(state);
        return dominantOutgoingProbabilities[state];
    }

    /// Two-cell oscillation score between distinct states A and B:
    /// (M[A][B] + M[B][A]) / (all transitions involving A or B)
    public double oscillation(int stateA, int stateB) {
        validateState(stateA);
        validateState(stateB);
        if (stateA == stateB) {
            throw new IllegalArgumentException(
                    "Oscillation requires distinct states, got: " + stateA + " and " + stateB);
        }

        long numerator = transitionCounts[stateA][stateB] + transitionCounts[stateB][stateA];
        long denominator = 0L;

        for (int i = 0; i < Band.TOTAL_STATES; i++) {
            for (int j = 0; j < Band.TOTAL_STATES; j++) {
                if (i == stateA || i == stateB || j == stateA || j == stateB) {
                    denominator += transitionCounts[i][j];
                }
            }
        }

        if (denominator == 0L) {
            return 0.0;
        }

        return (double) numerator / (double) denominator;
    }

    public boolean isEmpty() {
        for (int i = 0; i < Band.TOTAL_STATES; i++) {
            for (int j = 0; j < Band.TOTAL_STATES; j++) {
                if (transitionCounts[i][j] > 0L) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void validateState(int state) {
        if (state < 0 || state >= Band.TOTAL_STATES) {
            throw new IllegalArgumentException(
                    "State index out of bounds: " + state + " (expected 0.." + (Band.TOTAL_STATES - 1) + ")");
        }
    }
}
