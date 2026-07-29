package io.euhedral_execution.training.optimization.data;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.data.PolicyPredictionCurve;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.merge.VectorStatistics;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

public record PredictedPolicySummary(PolicyPredictionCurve curve,
                                     double predictedWorstQuality, double predictedQualityP25,
                                     double predictedGeometricMeanQuality, double predictedQualityMad,
                                     double maximumEpistemicStdDev, double maximumDisagreementRange,
                                     double meanOrdinalStdDev, double meanOrdinalEntropy, double pessimisticQuality) {
    private static final double EPSILON = 1.0e-12;

    public PredictedPolicySummary {
        Objects.requireNonNull(curve);
        for (double value : new double[]{predictedWorstQuality, predictedQualityP25,
                predictedGeometricMeanQuality, predictedQualityMad, maximumEpistemicStdDev,
                maximumDisagreementRange, meanOrdinalStdDev, meanOrdinalEntropy,
                pessimisticQuality}) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Predicted summary fields must be finite");
            }
        }
    }

    public static PredictedPolicySummary from(PolicyPredictionCurve curve,
            SortedSet<SourceScenario> requiredScenarios) {
        Objects.requireNonNull(curve);
        TreeSet<SourceScenario> expected = new TreeSet<>(requiredScenarios);
        if (expected.isEmpty() || curve.scenarios().size() != expected.size()) {
            throw new IllegalArgumentException("Prediction curve does not match required catalog");
        }
        int index = 0;
        double[] qualities = new double[curve.scenarios().size()];
        double worst = Double.POSITIVE_INFINITY;
        double maxEpistemic = 0.0;
        double maxDisagreement = 0.0;
        double[] ordinalStdDevs = new double[curve.scenarios().size()];
        double[] entropies = new double[curve.scenarios().size()];
        double pessimistic = Double.POSITIVE_INFINITY;
        for (SourceScenario scenario : expected) {
            ScenarioPrediction prediction = curve.scenarios().get(index);
            if (!scenario.equals(prediction.scenario())) {
                throw new IllegalArgumentException("Prediction scenarios are missing or reordered");
            }
            double quality = prediction.predictedQuality();
            qualities[index] = quality;
            worst = Math.min(worst, quality);
            maxEpistemic = Math.max(maxEpistemic, prediction.epistemicStdDev());
            maxDisagreement = Math.max(maxDisagreement, prediction.disagreementRange());
            ordinalStdDevs[index] = prediction.ordinalStdDev();
            entropies[index] = prediction.ordinalEntropy();
            index++;
            pessimistic = Math.min(pessimistic, prediction.qualityIntervalLow());
        }
        double[] logs = new double[qualities.length];
        for (int i = 0; i < qualities.length; i++) {
            logs[i] = StrictMath.log(Math.max(qualities[i], EPSILON));
        }
        return new PredictedPolicySummary(curve, worst,
                VectorStatistics.quantileType7(qualities, 0.25),
                StrictMath.exp(VectorStatistics.compensatedMean(logs)),
                VectorStatistics.mad(qualities), maxEpistemic, maxDisagreement,
                VectorStatistics.compensatedMean(ordinalStdDevs),
                VectorStatistics.compensatedMean(entropies), pessimistic);
    }

    public io.euhedral_execution.training.data.PolicyVector policy() {
        return curve.policy();
    }

    public List<ScenarioPrediction> predictions() {
        return curve.scenarios();
    }
}
