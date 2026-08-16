package calibration.statistics.iteration;

import calibration.statistics.VectorField;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Complete statistics for branch-decision observations (idle or execution).
public record DecisionStatistics(
        long totalObservations,
        @NonNull BranchOccupancyResult occupancy,
        @NonNull DecisionScalars head,
        @NonNull DecisionScalars steadyState,
        @NonNull DecisionScalars combined,
        @NonNull TransitionAnalysis headTransitions,
        @NonNull TransitionAnalysis steadyStateTransitions,
        @NonNull VectorField headVectorField,
        @NonNull VectorField steadyStateVectorField,
        @NonNull CorrelationResult headCorrelations,
        @NonNull CorrelationResult steadyStateCorrelations,
        @NonNull CorrelationResult combinedCorrelations) {

    public static final String[] COLUMN_NAMES = {"contentionPolicy", "bodyPolicy", "smoothedBodyCost"};

    public static final DecisionStatistics EMPTY = new DecisionStatistics(
            0L,
            BranchOccupancyResult.EMPTY,
            DecisionScalars.EMPTY,
            DecisionScalars.EMPTY,
            DecisionScalars.EMPTY,
            TransitionAnalysis.compute(new int[0]),
            TransitionAnalysis.compute(new int[0]),
            VectorField.compute(new int[0]),
            VectorField.compute(new int[0]),
            CorrelationResult.empty(COLUMN_NAMES),
            CorrelationResult.empty(COLUMN_NAMES),
            CorrelationResult.empty(COLUMN_NAMES));

    public DecisionStatistics {
        Objects.requireNonNull(occupancy, "occupancy must not be null");
        Objects.requireNonNull(head, "head must not be null");
        Objects.requireNonNull(steadyState, "steadyState must not be null");
        Objects.requireNonNull(combined, "combined must not be null");
        Objects.requireNonNull(headTransitions, "headTransitions must not be null");
        Objects.requireNonNull(steadyStateTransitions, "steadyStateTransitions must not be null");
        Objects.requireNonNull(headVectorField, "headVectorField must not be null");
        Objects.requireNonNull(steadyStateVectorField, "steadyStateVectorField must not be null");
        Objects.requireNonNull(headCorrelations, "headCorrelations must not be null");
        Objects.requireNonNull(steadyStateCorrelations, "steadyStateCorrelations must not be null");
        Objects.requireNonNull(combinedCorrelations, "combinedCorrelations must not be null");
    }

    public static DecisionStatistics empty() {
        return EMPTY;
    }
}
