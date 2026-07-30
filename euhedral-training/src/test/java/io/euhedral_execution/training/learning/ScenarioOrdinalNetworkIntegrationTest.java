package io.euhedral_execution.training.learning;

import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.learningRows;
import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.scenarios;
import static org.assertj.core.api.Assertions.assertThat;

import ai.djl.Device;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.data.ScenarioLearningMatrix;
import io.euhedral_execution.training.learning.enums.FeatureSelectionMode;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningRow;
import io.euhedral_execution.training.learning.metadata.FeatureNormalizer;
import io.euhedral_execution.training.learning.metadata.MemberMetadata;
import io.euhedral_execution.training.learning.utils.ScenarioFeatureEncoder;
import io.euhedral_execution.training.learning.utils.ScenarioOrdinalTargets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioOrdinalNetworkIntegrationTest {
    @TempDir Path temporary;

    @Test
    void trainsSavesAndReloadsDynamicInputMember() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("training.djlIntegration"),
                "Enable with -Dtraining.djlIntegration=true");
        List<ScenarioLearningRow> rows = learningRows();
        List<ScenarioLearningRow> fitting = rows.stream()
                .filter(row -> row.policy().weight(0) < 0.75).toList();
        List<ScenarioLearningRow> validation = rows.stream()
                .filter(row -> row.policy().weight(0) >= 0.75).toList();
        FeatureNormalizer normalizer =
                FeatureNormalizer.fit(fitting, ScenarioFeatureSet.RATIO_ONLY);
        ScenarioLearningMatrix fittingMatrix =
                ScenarioFeatureEncoder.matrix(fitting, scenarios(), normalizer);
        ScenarioLearningMatrix validationMatrix =
                ScenarioFeatureEncoder.matrix(validation, scenarios(), normalizer);
        ScenarioTrainingConfig config = ScenarioTrainingConfig.forTest(1, 2,
                FeatureSelectionMode.RATIO_ONLY);
        ScenarioOrdinalNetwork.TrainingResult result = ScenarioOrdinalNetwork.train(
                fittingMatrix, validationMatrix, ScenarioFeatureSet.RATIO_ONLY, config,
                Device.cpu(), "PRODUCTION", "all", 0, temporary.resolve("member-000"));
        float[] features = new float[2 * 29];
        normalizer.encode(validation.getFirst().policy(), scenarios().first(), features, 0);
        normalizer.encode(validation.getFirst().policy(), scenarios().last(), features, 29);
        float[] savedLogits = new float[18];
        try (ScenarioOrdinalNetwork member = result.member()) {
            member.predictLogits(features, 2, savedLogits);
            assertThat(savedLogits).hasSize(18);
            for (float logit : savedLogits) assertThat(logit).isFinite();
            double[] first = new double[9], second = new double[9];
            for (int output = 0; output < 9; output++) {
                first[output] = savedLogits[output];
                second[output] = savedLogits[9 + output];
            }
            assertThat(ScenarioOrdinalTargets.decode(first).meanQuality())
                    .isNotEqualTo(ScenarioOrdinalTargets.decode(second).meanQuality());
        }
        MemberMetadata memberMetadata = new MemberMetadata(0, result.seed(),
                result.bestEpoch(), MemberMetadata.expectedPath(0), "0".repeat(64));
        try (ScenarioOrdinalNetwork reloaded = ScenarioOrdinalNetwork.load(
                temporary.resolve("member-000"), ScenarioFeatureSet.RATIO_ONLY,
                memberMetadata, Device.cpu())) {
            float[] reloadedLogits = new float[18];
            reloaded.predictLogits(features, 2, reloadedLogits);
            assertThat(reloadedLogits).containsExactly(savedLogits);
        }
        MemberMetadata wrongSeed = new MemberMetadata(0, result.seed() + 1,
                result.bestEpoch(), MemberMetadata.expectedPath(0), "0".repeat(64));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ScenarioOrdinalNetwork.load(temporary.resolve("member-000"),
                        ScenarioFeatureSet.RATIO_ONLY, wrongSeed, Device.cpu()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Failed to load");
    }
}
