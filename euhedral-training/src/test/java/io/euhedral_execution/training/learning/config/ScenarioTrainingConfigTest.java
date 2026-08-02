package io.euhedral_execution.training.learning.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScenarioTrainingConfigTest {
    @Test
    void recognizesResolvedAutoBatchAsTheEffectiveRequestedConfiguration() {
        ScenarioTrainingConfig requested = ScenarioTrainingConfig.defaults().coldStart();
        ScenarioTrainingConfig effective = withBatchSize(requested, 512);

        assertThat(effective.isEffectiveVersionOf(requested)).isTrue();
        assertThat(requested.isEffectiveVersionOf(requested)).isTrue();
        assertThat(ScenarioTrainingConfig.defaults().isEffectiveVersionOf(requested)).isFalse();
    }

    @Test
    void rejectsAnEffectiveBatchLargerThanAnExplicitRequest() {
        ScenarioTrainingConfig requested = withBatchSize(
                ScenarioTrainingConfig.defaults().coldStart(), 128);

        assertThat(withBatchSize(requested, 64).isEffectiveVersionOf(requested)).isTrue();
        assertThat(withBatchSize(requested, 256).isEffectiveVersionOf(requested)).isFalse();
    }

    private static ScenarioTrainingConfig withBatchSize(ScenarioTrainingConfig source,
            int batchSize) {
        return new ScenarioTrainingConfig(source.splitSeed(), source.modelSeed(), source.device(),
                source.ensembleMembers(), source.losoEvaluationMembers(),
                source.ablationMembers(), source.maxEpochs(), source.patience(), batchSize,
                source.learningRate(), source.weightDecay(), source.labelSmoothing(),
                source.minimumTrainPolicyGroups(), source.minimumValidationPolicyGroups(),
                source.minimumTestPolicyGroups(), source.minimumTrainRowsPerScenario(),
                source.minimumValidationRowsPerScenario(), source.minimumTestRowsPerScenario(),
                source.includeWeakCalibrationRows(), source.requireTargetVariation(),
                source.featureSelectionMode(), source.thresholds());
    }
}
