package io.euhedral_execution.training.learning.statistics;

import java.util.Objects;
import java.util.OptionalDouble;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.enums.EvaluationStatus;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;

public record ScenarioEvaluationMetrics(String evaluationKind, String foldId,
                                        ScenarioFeatureSet featureSet, SourceScenario scenario,
                                        int rowCount, int policyCount, double mae, double rmse,
                                        double meanBias, OptionalDouble spearman,
                                        int actualTopDecileCount, int selectedCount,
                                        OptionalDouble precisionAtTen, OptionalDouble recallAtTen,
                                        double meanIntervalWidth, double intervalCoverage95,
                                        double meanEpistemicStdDev, double meanDisagreementRange,
                                        EvaluationStatus status) {

    private static void validateCorrelation(OptionalDouble value) {
        if (value.isPresent() && (!Double.isFinite(value.getAsDouble()) || value.getAsDouble() < -1
                || value.getAsDouble() > 1)) {
            throw new IllegalArgumentException("Invalid correlation");
        }
    }

    private static void validateRate(OptionalDouble value) {
        if (value.isPresent() && (!Double.isFinite(value.getAsDouble()) || value.getAsDouble() < 0
                || value.getAsDouble() > 1)) {
            throw new IllegalArgumentException("Invalid rate");
        }
    }

    public ScenarioEvaluationMetrics {
        Objects.requireNonNull(evaluationKind);
        Objects.requireNonNull(foldId);
        Objects.requireNonNull(featureSet);
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(spearman);
        Objects.requireNonNull(precisionAtTen);
        Objects.requireNonNull(recallAtTen);
        Objects.requireNonNull(status);
        if (policyCount < 1 || policyCount > rowCount || actualTopDecileCount < 0
                || actualTopDecileCount > rowCount || selectedCount != StrictMath.max(1,
                (int) StrictMath.ceil(0.10 * rowCount))) {
            throw new IllegalArgumentException();
        }
        for (double x : new double[] {mae, rmse, meanBias, meanIntervalWidth, intervalCoverage95,
                meanEpistemicStdDev, meanDisagreementRange}) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException();
            }
        }
        if (mae < 0 || mae > 1 || rmse < 0 || rmse > 1 || meanBias < -1 || meanBias > 1
                || meanIntervalWidth < 0 || meanIntervalWidth > 1 || intervalCoverage95 < 0
                || intervalCoverage95 > 1 || meanEpistemicStdDev < 0 || meanEpistemicStdDev > 1
                || meanDisagreementRange < 0 || meanDisagreementRange > 1
                || precisionAtTen.isEmpty() || actualTopDecileCount == 0 != recallAtTen.isEmpty()) {
            throw new IllegalArgumentException("Metric outside valid range");
        }
        validateCorrelation(spearman);
        validateRate(precisionAtTen);
        validateRate(recallAtTen);
    }
}
