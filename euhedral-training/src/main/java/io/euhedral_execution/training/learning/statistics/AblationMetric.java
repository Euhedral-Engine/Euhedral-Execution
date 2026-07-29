package io.euhedral_execution.training.learning.statistics;

import java.util.Objects;
import java.util.OptionalDouble;

import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;

public record AblationMetric(String evaluationKind, String foldId, ScenarioFeatureSet featureSet,
                             ScenarioFeatureSet comparisonFeatureSet, String scenarioOrEnvironment,
                             int rowCount, OptionalDouble mae, OptionalDouble spearman,
                             OptionalDouble maeDelta, OptionalDouble spearmanDelta,
                             boolean selected, String gateStatus, String reason) {

    private static void validate(OptionalDouble value, double minimum, double maximum,
            String name) {
        if (value.isPresent() && (!Double.isFinite(value.getAsDouble())
                || value.getAsDouble() < minimum || value.getAsDouble() > maximum)) {
            throw new IllegalArgumentException("Invalid ablation " + name);
        }
    }

    public AblationMetric {
        Objects.requireNonNull(evaluationKind);
        Objects.requireNonNull(foldId);
        Objects.requireNonNull(featureSet);
        Objects.requireNonNull(comparisonFeatureSet);
        Objects.requireNonNull(scenarioOrEnvironment);
        Objects.requireNonNull(mae);
        Objects.requireNonNull(spearman);
        Objects.requireNonNull(maeDelta);
        Objects.requireNonNull(spearmanDelta);
        Objects.requireNonNull(gateStatus);
        Objects.requireNonNull(reason);
        if (evaluationKind.isBlank() || foldId.isBlank() || scenarioOrEnvironment.isBlank()
                || gateStatus.isBlank() || reason.isBlank() || rowCount < 0) {
            throw new IllegalArgumentException("Invalid ablation identity");
        }
        validate(mae, 0, 1, "MAE");
        validate(spearman, -1, 1, "Spearman");
        validate(maeDelta, -1, 1, "MAE delta");
        validate(spearmanDelta, -2, 2, "Spearman delta");
    }
}
