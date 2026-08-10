package io.euhedral_execution.training.learning.config;

public record EvaluationThresholds(
        double maximumGroupedMacroMae,
        double minimumGroupedMacroSpearman,
        double minimumGroupedMacroPrecisionAtTen,
        double maximumLosoMacroMae,
        double minimumLosoMacroSpearman,
        double maximumLosoWorstScenarioMae,
        double minimumContextMaeImprovement,
        double minimumContextSpearmanImprovement,
        double maximumContextMaeRegression,
        double maximumContextSpearmanRegression,
        double minimumCountsCrossEnvironmentMaeImprovement,
        double maximumCountsSpearmanRegression,
        double maximumCountsWorstEnvironmentMaeRegression) {

    public EvaluationThresholds {
        for (double x : new double[] {
            maximumGroupedMacroMae,
            minimumGroupedMacroSpearman,
            minimumGroupedMacroPrecisionAtTen,
            maximumLosoMacroMae,
            minimumLosoMacroSpearman,
            maximumLosoWorstScenarioMae,
            minimumContextMaeImprovement,
            minimumContextSpearmanImprovement,
            maximumContextMaeRegression,
            maximumContextSpearmanRegression,
            minimumCountsCrossEnvironmentMaeImprovement,
            maximumCountsSpearmanRegression,
            maximumCountsWorstEnvironmentMaeRegression
        }) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException("Non-finite threshold");
            }
        }
        if (!rate(maximumGroupedMacroMae)
                || minimumGroupedMacroSpearman < -1
                || minimumGroupedMacroSpearman > 1
                || !rate(minimumGroupedMacroPrecisionAtTen)
                || !rate(maximumLosoMacroMae)
                || minimumLosoMacroSpearman < -1
                || minimumLosoMacroSpearman > 1
                || !rate(maximumLosoWorstScenarioMae)
                || minimumContextMaeImprovement < 0
                || minimumContextSpearmanImprovement < 0
                || maximumContextMaeRegression < 0
                || maximumContextSpearmanRegression < 0
                || minimumCountsCrossEnvironmentMaeImprovement < 0
                || maximumCountsSpearmanRegression < 0
                || maximumCountsWorstEnvironmentMaeRegression < 0) {
            throw new IllegalArgumentException("Threshold outside valid range");
        }
    }

    public static EvaluationThresholds defaults() {
        return new EvaluationThresholds(.20, .50, .20, .25, .35, .35, .01, .05, .01, .02, .01, .02, .02);
    }

    private static boolean rate(double value) {
        return value >= 0 && value <= 1;
    }
}
