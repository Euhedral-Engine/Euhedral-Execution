package io.euhedral_execution.training.learning;
import java.util.*;
public record EvaluationSummary(String evaluationKind, ScenarioFeatureSet featureSet,
        List<ScenarioEvaluationMetrics> scenarios, OptionalDouble macroMae,
        OptionalDouble macroRmse, OptionalDouble macroSpearman,
        OptionalDouble macroPrecisionAtTen, OptionalDouble macroRecallAtTen,
        OptionalDouble worstScenarioMae, OptionalDouble microMae) {
    public EvaluationSummary {
        Objects.requireNonNull(evaluationKind); Objects.requireNonNull(featureSet);
        scenarios = scenarios.stream()
                .sorted(Comparator.comparing(ScenarioEvaluationMetrics::scenario)).toList();
        Objects.requireNonNull(macroMae); Objects.requireNonNull(macroRmse);
        Objects.requireNonNull(macroSpearman); Objects.requireNonNull(macroPrecisionAtTen);
        Objects.requireNonNull(macroRecallAtTen); Objects.requireNonNull(worstScenarioMae);
        Objects.requireNonNull(microMae);
        if (evaluationKind.isBlank() || scenarios.stream().anyMatch(metric ->
                !metric.evaluationKind().equals(evaluationKind)
                        || metric.featureSet() != featureSet)) {
            throw new IllegalArgumentException("Evaluation-summary identities disagree");
        }
        validateRate(macroMae);
        validateRate(macroRmse);
        validateCorrelation(macroSpearman);
        validateRate(macroPrecisionAtTen);
        validateRate(macroRecallAtTen);
        validateRate(worstScenarioMae);
        validateRate(microMae);
    }

    private static void validateRate(OptionalDouble value) {
        if (value.isPresent() && (!Double.isFinite(value.getAsDouble())
                || value.getAsDouble() < 0 || value.getAsDouble() > 1)) {
            throw new IllegalArgumentException("Invalid evaluation-summary rate");
        }
    }

    private static void validateCorrelation(OptionalDouble value) {
        if (value.isPresent() && (!Double.isFinite(value.getAsDouble())
                || value.getAsDouble() < -1 || value.getAsDouble() > 1)) {
            throw new IllegalArgumentException("Invalid evaluation-summary correlation");
        }
    }
}
