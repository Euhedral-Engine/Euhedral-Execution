package calibration.comparisons;

import calibration.comparisons.schema.StateComparability;
import calibration.comparisons.schema.StateComparabilityComparison;
import calibration.statistics.Band;
import calibration.statistics.VectorCell;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.TransitionAnalysis;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Classifies policy comparisons using explicit state-distribution components.
public final class StateComparabilityCalculator {

    // Phase 10's low-TV cluster ended at 0.138 and its large-shift cluster began at 0.669.
    // The wider 0.25/0.60 analysis bands admit observed within-fork variation without turning
    // these analysis tolerances into scheduler policy constants.
    static final double COMPARABLE_OCCUPANCY_TV_MAX = 0.25;
    static final double DIVERGENT_OCCUPANCY_TV_MIN = 0.60;
    static final double PRODUCTIVE_RATIO_EQUIVALENCE_MAX = 0.01;
    static final double COMPARABLE_CONTENTION_CENTROID_DELTA_MAX = 0.50;
    static final double DIVERGENT_CONTENTION_CENTROID_DELTA_MIN = 1.25;
    static final double COMPARABLE_BODY_CENTROID_DELTA_MAX = 0.25;
    static final double DIVERGENT_BODY_CENTROID_DELTA_MIN = 0.75;

    private StateComparabilityCalculator() {}

    public static @Nullable StateComparabilityComparison compare(
            @NonNull SystemForkResult baseline, @NonNull SystemForkResult candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        BranchOccupancyResult baselineOccupancy = baseline.execDecisions().occupancy();
        BranchOccupancyResult candidateOccupancy = candidate.execDecisions().occupancy();
        if (baselineOccupancy.totalCount() == 0L || candidateOccupancy.totalCount() == 0L) {
            return null;
        }

        double baselineProductiveRatio =
                baseline.batchComplete().steadyState().productiveHandleRatio().mean();
        double candidateProductiveRatio =
                candidate.batchComplete().steadyState().productiveHandleRatio().mean();
        if (!Double.isFinite(baselineProductiveRatio) || !Double.isFinite(candidateProductiveRatio)) {
            return null;
        }

        double occupancyTv = occupancyTotalVariationDistance(baselineOccupancy, candidateOccupancy);
        double contentionDelta = candidateOccupancy.contentionCentroid() - baselineOccupancy.contentionCentroid();
        double bodyDelta = candidateOccupancy.bodyCentroid() - baselineOccupancy.bodyCentroid();
        double productiveRatioDelta = candidateProductiveRatio - baselineProductiveRatio;

        int baselineDominantState = dominantState(baselineOccupancy);
        int candidateDominantState = dominantState(candidateOccupancy);
        double baselineDominantProbability = probability(baselineOccupancy, baselineDominantState);
        double candidateDominantProbability = probability(candidateOccupancy, candidateDominantState);

        TransitionAnalysis baselineTransitions = baseline.execDecisions().steadyStateTransitions();
        TransitionAnalysis candidateTransitions = candidate.execDecisions().steadyStateTransitions();
        double baselineSelfTransition = baselineTransitions.selfTransitionRate(baselineDominantState);
        double candidateSelfTransition = candidateTransitions.selfTransitionRate(candidateDominantState);
        double transitionTv = transitionTotalVariationDistance(baselineTransitions, candidateTransitions);
        double baselineMaximumOscillation = maximumOscillation(baselineTransitions);
        double candidateMaximumOscillation = maximumOscillation(candidateTransitions);

        VectorCell baselineVector =
                baseline.execDecisions().steadyStateVectorField().cell(baselineDominantState);
        VectorCell candidateVector =
                candidate.execDecisions().steadyStateVectorField().cell(candidateDominantState);

        StateComparability classification = classify(productiveRatioDelta, contentionDelta, bodyDelta, occupancyTv);
        return new StateComparabilityComparison(
                classification,
                baselineProductiveRatio,
                candidateProductiveRatio,
                productiveRatioDelta,
                baselineOccupancy.contentionCentroid(),
                candidateOccupancy.contentionCentroid(),
                contentionDelta,
                baselineOccupancy.bodyCentroid(),
                candidateOccupancy.bodyCentroid(),
                bodyDelta,
                occupancyTv,
                baselineDominantState,
                candidateDominantState,
                baselineDominantProbability,
                candidateDominantProbability,
                baselineSelfTransition,
                candidateSelfTransition,
                candidateSelfTransition - baselineSelfTransition,
                transitionTv,
                baselineMaximumOscillation,
                candidateMaximumOscillation,
                candidateMaximumOscillation - baselineMaximumOscillation,
                vectorContention(baselineVector),
                vectorContention(candidateVector),
                vectorBody(baselineVector),
                vectorBody(candidateVector),
                vectorMagnitude(baselineVector),
                vectorMagnitude(candidateVector));
    }

    static StateComparability classify(
            double productiveRatioDelta, double contentionDelta, double bodyDelta, double occupancyTv) {
        if (Math.abs(productiveRatioDelta) > PRODUCTIVE_RATIO_EQUIVALENCE_MAX
                || occupancyTv >= DIVERGENT_OCCUPANCY_TV_MIN
                || Math.abs(contentionDelta) >= DIVERGENT_CONTENTION_CENTROID_DELTA_MIN
                || Math.abs(bodyDelta) >= DIVERGENT_BODY_CENTROID_DELTA_MIN) {
            return StateComparability.STATE_DIVERGENT;
        }
        if (occupancyTv <= COMPARABLE_OCCUPANCY_TV_MAX
                && Math.abs(contentionDelta) <= COMPARABLE_CONTENTION_CENTROID_DELTA_MAX
                && Math.abs(bodyDelta) <= COMPARABLE_BODY_CENTROID_DELTA_MAX) {
            return StateComparability.STATE_COMPARABLE;
        }
        return StateComparability.STATE_SHIFTED;
    }

    private static double occupancyTotalVariationDistance(
            BranchOccupancyResult baseline, BranchOccupancyResult candidate) {
        double[][] baselineProbabilities = baseline.normalizedOccupancy();
        double[][] candidateProbabilities = candidate.normalizedOccupancy();
        double sumAbsoluteDifference = 0.0;
        for (int contention = 0; contention < Band.GRID_SIZE; contention++) {
            for (int body = 0; body < Band.GRID_SIZE; body++) {
                sumAbsoluteDifference +=
                        Math.abs(candidateProbabilities[contention][body] - baselineProbabilities[contention][body]);
            }
        }
        return 0.5 * sumAbsoluteDifference;
    }

    private static int dominantState(BranchOccupancyResult occupancy) {
        double[][] probabilities = occupancy.normalizedOccupancy();
        int dominantState = 0;
        double dominantProbability = probabilities[0][0];
        for (int contention = 0; contention < Band.GRID_SIZE; contention++) {
            for (int body = 0; body < Band.GRID_SIZE; body++) {
                if (probabilities[contention][body] > dominantProbability) {
                    dominantProbability = probabilities[contention][body];
                    dominantState = contention * Band.GRID_SIZE + body;
                }
            }
        }
        return dominantState;
    }

    private static double probability(BranchOccupancyResult occupancy, int state) {
        return occupancy.normalizedOccupancy()[state / Band.GRID_SIZE][state % Band.GRID_SIZE];
    }

    private static double transitionTotalVariationDistance(TransitionAnalysis baseline, TransitionAnalysis candidate) {
        long[][] baselineCounts = baseline.transitionCounts();
        long[][] candidateCounts = candidate.transitionCounts();
        long baselineTotal = total(baselineCounts);
        long candidateTotal = total(candidateCounts);
        if (baselineTotal == 0L || candidateTotal == 0L) {
            return Double.NaN;
        }

        double sumAbsoluteDifference = 0.0;
        for (int from = 0; from < Band.TOTAL_STATES; from++) {
            for (int to = 0; to < Band.TOTAL_STATES; to++) {
                double baselineProbability = (double) baselineCounts[from][to] / baselineTotal;
                double candidateProbability = (double) candidateCounts[from][to] / candidateTotal;
                sumAbsoluteDifference += Math.abs(candidateProbability - baselineProbability);
            }
        }
        return 0.5 * sumAbsoluteDifference;
    }

    private static long total(long[][] counts) {
        long total = 0L;
        for (long[] row : counts) {
            for (long count : row) {
                total += count;
            }
        }
        return total;
    }

    private static double maximumOscillation(TransitionAnalysis transitions) {
        double maximum = 0.0;
        for (int first = 0; first < Band.TOTAL_STATES; first++) {
            for (int second = first + 1; second < Band.TOTAL_STATES; second++) {
                maximum = Math.max(maximum, transitions.oscillation(first, second));
            }
        }
        return maximum;
    }

    private static double vectorContention(@Nullable VectorCell cell) {
        return cell != null && cell.hasVector() ? cell.meanDeltaContention() : Double.NaN;
    }

    private static double vectorBody(@Nullable VectorCell cell) {
        return cell != null && cell.hasVector() ? cell.meanDeltaBody() : Double.NaN;
    }

    private static double vectorMagnitude(@Nullable VectorCell cell) {
        return cell != null && cell.hasVector() ? cell.magnitude() : Double.NaN;
    }
}
