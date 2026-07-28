package io.euhedral_execution.training.learning;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record FeatureSelectionDecision(FeatureSelectionMode requestedMode,
        ScenarioFeatureSet selectedFeatureSet, List<AblationMetric> metrics, String reason) {
    public FeatureSelectionDecision {
        Objects.requireNonNull(requestedMode);
        Objects.requireNonNull(selectedFeatureSet);
        Objects.requireNonNull(metrics);
        Objects.requireNonNull(reason);
        metrics = metrics.stream().sorted(Comparator
                .comparing(AblationMetric::evaluationKind)
                .thenComparing(AblationMetric::foldId)
                .thenComparing(metric -> metric.featureSet().schemaId())).toList();
        if (selectedFeatureSet == ScenarioFeatureSet.POLICY_ONLY || reason.isBlank()
                || requestedMode == FeatureSelectionMode.RATIO_ONLY
                && selectedFeatureSet != ScenarioFeatureSet.RATIO_ONLY
                || requestedMode == FeatureSelectionMode.REQUIRE_COUNTS
                && selectedFeatureSet != ScenarioFeatureSet.RATIO_AND_COUNTS
                || metrics.stream().noneMatch(metric ->
                metric.evaluationKind().equals("VALIDATION_CONTEXT_GATE"))) {
            throw new IllegalArgumentException("Invalid feature selection decision");
        }
    }
}
