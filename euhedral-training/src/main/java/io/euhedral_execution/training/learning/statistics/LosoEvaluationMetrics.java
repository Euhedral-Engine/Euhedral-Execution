package io.euhedral_execution.training.learning.statistics;

import java.util.Objects;

public record LosoEvaluationMetrics(ScenarioEvaluationMetrics metrics, double heldOutRatio,
                                    boolean ratioSeenInFit, int fittingScenarioCount,
                                    int fittingDistinctRatioCount) {

    public LosoEvaluationMetrics {
        Objects.requireNonNull(metrics);
        if (!Double.isFinite(heldOutRatio) || heldOutRatio <= 0
                || Double.compare(heldOutRatio, metrics.scenario().ratio().asDouble()) != 0
                || fittingDistinctRatioCount < 0
                || fittingDistinctRatioCount > fittingScenarioCount) {
            throw new IllegalArgumentException("Invalid LOSO context audit");
        }
    }
}
