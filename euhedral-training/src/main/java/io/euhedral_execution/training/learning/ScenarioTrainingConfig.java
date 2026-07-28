package io.euhedral_execution.training.learning;
import java.util.Locale;
import java.util.Objects;
public record ScenarioTrainingConfig(long splitSeed, long modelSeed, String device,
        int ensembleMembers, int losoEvaluationMembers, int ablationMembers, int maxEpochs,
        int patience, int batchSize, float learningRate, float weightDecay, float labelSmoothing,
        int minimumTrainPolicyGroups, int minimumValidationPolicyGroups,
        int minimumTestPolicyGroups, int minimumTrainRowsPerScenario,
        int minimumValidationRowsPerScenario, int minimumTestRowsPerScenario,
        boolean includeWeakCalibrationRows, FeatureSelectionMode featureSelectionMode,
        EvaluationThresholds thresholds) {
    public ScenarioTrainingConfig {
        Objects.requireNonNull(device);
        Objects.requireNonNull(featureSelectionMode);
        Objects.requireNonNull(thresholds);
        device = device.trim().toLowerCase(Locale.ROOT);
        if (!device.equals("auto") && !device.equals("cpu") && !device.matches("gpu[0-9]+")
                || ensembleMembers < 3 || ensembleMembers > 9 || ensembleMembers % 2 == 0
                || losoEvaluationMembers < 1 || losoEvaluationMembers > ensembleMembers
                || ablationMembers < 3 || ablationMembers > ensembleMembers || ablationMembers % 2 == 0
                || maxEpochs <= 0 || patience <= 0 || batchSize < 0
                || !Float.isFinite(learningRate) || learningRate <= 0
                || !Float.isFinite(weightDecay) || weightDecay < 0
                || !Float.isFinite(labelSmoothing)
                || labelSmoothing < 0 || labelSmoothing >= .5
                || minimumTrainPolicyGroups < 1 || minimumValidationPolicyGroups < 2
                || minimumTestPolicyGroups < 1 || minimumTrainRowsPerScenario < 1
                || minimumValidationRowsPerScenario < 1 || minimumTestRowsPerScenario < 1)
            throw new IllegalArgumentException("Invalid scenario training configuration");
    }
    public static ScenarioTrainingConfig defaults() {
        return new ScenarioTrainingConfig(0x243f6a8885a308d3L, 0x13198a2e03707344L, "auto",
                5, 1, 3, 250, 20, 0, .001f, .0001f, .02f, 40, 10, 10, 30, 5, 5,
                false, FeatureSelectionMode.RATIO_ONLY, EvaluationThresholds.defaults());
    }

    static ScenarioTrainingConfig forTest(long splitSeed, long modelSeed,
            FeatureSelectionMode mode) {
        return new ScenarioTrainingConfig(splitSeed, modelSeed, "cpu", 3, 1, 3,
                5, 2, 16, 0.001f, 0.0001f, 0.02f,
                1, 2, 1, 1, 1, 1, false, mode, EvaluationThresholds.defaults());
    }
}
