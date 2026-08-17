package calibration.comparisons.schema;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Complete structured comparison results for a single matching physical core.
public record CoreComparison(
        int core,
        @NonNull Map<String, ScalarComparison> scalarComparisons,
        @NonNull OccupancyComparison idleOccupancy,
        @NonNull OccupancyComparison execOccupancy,
        @NonNull TransitionComparison idleHeadTransitions,
        @NonNull TransitionComparison idleSteadyStateTransitions,
        @NonNull TransitionComparison execHeadTransitions,
        @NonNull TransitionComparison execSteadyStateTransitions,
        @NonNull VectorFieldComparison idleHeadVectorField,
        @NonNull VectorFieldComparison idleSteadyStateVectorField,
        @NonNull VectorFieldComparison execHeadVectorField,
        @NonNull VectorFieldComparison execSteadyStateVectorField,
        @NonNull Map<String, CorrelationComparison> correlationComparisons,
        double baselineCentroidDistance,
        double candidateCentroidDistance,
        double centroidDistanceDelta) {

    public CoreComparison {
        scalarComparisons = scalarComparisons == null ? Map.of() : Map.copyOf(scalarComparisons);
        Objects.requireNonNull(idleOccupancy, "idleOccupancy must not be null");
        Objects.requireNonNull(execOccupancy, "execOccupancy must not be null");
        Objects.requireNonNull(idleHeadTransitions, "idleHeadTransitions must not be null");
        Objects.requireNonNull(idleSteadyStateTransitions, "idleSteadyStateTransitions must not be null");
        Objects.requireNonNull(execHeadTransitions, "execHeadTransitions must not be null");
        Objects.requireNonNull(execSteadyStateTransitions, "execSteadyStateTransitions must not be null");
        Objects.requireNonNull(idleHeadVectorField, "idleHeadVectorField must not be null");
        Objects.requireNonNull(idleSteadyStateVectorField, "idleSteadyStateVectorField must not be null");
        Objects.requireNonNull(execHeadVectorField, "execHeadVectorField must not be null");
        Objects.requireNonNull(execSteadyStateVectorField, "execSteadyStateVectorField must not be null");
        correlationComparisons = correlationComparisons == null ? Map.of() : Map.copyOf(correlationComparisons);
    }
}
