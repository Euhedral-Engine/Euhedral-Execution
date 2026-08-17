package calibration.comparisons.schema;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Whole-run aggregate diagnostic comparison across all physical cores.
public record AggregateComparison(
        @NonNull OccupancyComparison idleOccupancy,
        @NonNull OccupancyComparison execOccupancy,
        @Nullable TransitionComparison idleHeadTransitions,
        @Nullable TransitionComparison idleSteadyStateTransitions,
        @Nullable TransitionComparison execHeadTransitions,
        @Nullable TransitionComparison execSteadyStateTransitions,
        @Nullable VectorFieldComparison idleHeadVectorField,
        @Nullable VectorFieldComparison idleSteadyStateVectorField,
        @Nullable VectorFieldComparison execHeadVectorField,
        @Nullable VectorFieldComparison execSteadyStateVectorField,
        @NonNull Map<String, ScalarComparison> scalarComparisons,
        @NonNull Map<String, CorrelationComparison> correlationComparisons) {

    public AggregateComparison {
        Objects.requireNonNull(idleOccupancy, "idleOccupancy must not be null");
        Objects.requireNonNull(execOccupancy, "execOccupancy must not be null");
        scalarComparisons = scalarComparisons == null ? Map.of() : Map.copyOf(scalarComparisons);
        correlationComparisons = correlationComparisons == null ? Map.of() : Map.copyOf(correlationComparisons);
    }
}
