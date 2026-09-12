package calibration.comparisons.schema;

import calibration.statistics.DecisionGrid;
import calibration.statistics.iteration.TransitionAnalysis;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Comparison between matching baseline and candidate 10-state transition analyses.
public record TransitionComparison(
        @NonNull TransitionAnalysis baseline,
        @NonNull TransitionAnalysis candidate,
        long[][] countDeltas,
        double[][] probabilityDeltas,
        double[] selfTransitionRateDeltas,
        int[] candidateDominantOutgoingStates,
        double[] dominantOutgoingProbabilityDeltas,
        double[][] oscillationScoreDeltas) {

    public TransitionComparison {
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
            countDeltas = new long[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];
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
            probabilityDeltas = new double[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];
        }

        if (selfTransitionRateDeltas != null) {
            selfTransitionRateDeltas = selfTransitionRateDeltas.clone();
        } else {
            selfTransitionRateDeltas = new double[DecisionGrid.TOTAL_STATES];
        }

        if (candidateDominantOutgoingStates != null) {
            candidateDominantOutgoingStates = candidateDominantOutgoingStates.clone();
        } else {
            candidateDominantOutgoingStates = new int[DecisionGrid.TOTAL_STATES];
        }

        if (dominantOutgoingProbabilityDeltas != null) {
            dominantOutgoingProbabilityDeltas = dominantOutgoingProbabilityDeltas.clone();
        } else {
            dominantOutgoingProbabilityDeltas = new double[DecisionGrid.TOTAL_STATES];
        }

        if (oscillationScoreDeltas != null) {
            double[][] copy = new double[oscillationScoreDeltas.length][];
            for (int i = 0; i < oscillationScoreDeltas.length; i++) {
                if (oscillationScoreDeltas[i] != null) {
                    copy[i] = oscillationScoreDeltas[i].clone();
                }
            }
            oscillationScoreDeltas = copy;
        } else {
            oscillationScoreDeltas = new double[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];
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
    public double[] selfTransitionRateDeltas() {
        return selfTransitionRateDeltas.clone();
    }

    @Override
    public int[] candidateDominantOutgoingStates() {
        return candidateDominantOutgoingStates.clone();
    }

    @Override
    public double[] dominantOutgoingProbabilityDeltas() {
        return dominantOutgoingProbabilityDeltas.clone();
    }

    @Override
    public double[][] oscillationScoreDeltas() {
        double[][] copy = new double[oscillationScoreDeltas.length][];
        for (int i = 0; i < oscillationScoreDeltas.length; i++) {
            copy[i] = oscillationScoreDeltas[i].clone();
        }
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransitionComparison that)) return false;
        return baseline.equals(that.baseline)
                && candidate.equals(that.candidate)
                && Arrays.deepEquals(countDeltas, that.countDeltas)
                && Arrays.deepEquals(probabilityDeltas, that.probabilityDeltas)
                && Arrays.equals(selfTransitionRateDeltas, that.selfTransitionRateDeltas)
                && Arrays.equals(candidateDominantOutgoingStates, that.candidateDominantOutgoingStates)
                && Arrays.equals(dominantOutgoingProbabilityDeltas, that.dominantOutgoingProbabilityDeltas)
                && Arrays.deepEquals(oscillationScoreDeltas, that.oscillationScoreDeltas);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(baseline, candidate);
        result = 31 * result + Arrays.deepHashCode(countDeltas);
        result = 31 * result + Arrays.deepHashCode(probabilityDeltas);
        result = 31 * result + Arrays.hashCode(selfTransitionRateDeltas);
        result = 31 * result + Arrays.hashCode(candidateDominantOutgoingStates);
        result = 31 * result + Arrays.hashCode(dominantOutgoingProbabilityDeltas);
        result = 31 * result + Arrays.deepHashCode(oscillationScoreDeltas);
        return result;
    }
}
