package io.euhedral_execution.training.learning;

import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.learningRows;
import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.policies;
import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.scenarios;
import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.data.ScenarioLearningMatrix;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningRow;
import io.euhedral_execution.training.learning.metadata.FeatureNormalizer;
import io.euhedral_execution.training.learning.utils.ScenarioFeatureEncoder;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ScenarioFeatureEncoderTest {
    @Test
    void exposesExactFeatureSchemasAndRawOrder() {
        assertThat(ScenarioFeatureSet.POLICY_ONLY.featureNames()).hasSize(28)
                .startsWith("policy_weight_00", "policy_weight_01");
        assertThat(ScenarioFeatureSet.RATIO_ONLY.featureNames()).hasSize(29)
                .endsWith("source_core_ratio");
        assertThat(ScenarioFeatureSet.RATIO_AND_COUNTS.featureNames()).hasSize(31)
                .endsWith("source_count_log1p",
                        "available_physical_core_count_log1p");
        List<ScenarioLearningRow> rows = learningRows().subList(0, 16);
        FeatureNormalizer normalizer =
                FeatureNormalizer.fit(rows, ScenarioFeatureSet.RATIO_AND_COUNTS);
        ScenarioLearningMatrix matrix = ScenarioFeatureEncoder.matrix(
                rows, new TreeSet<>(List.of(rows.getFirst().scenario())), normalizer);
        assertThat(matrix.features()).hasSize(matrix.rows() * 31);
        assertThat(matrix.ordinalLabels()).hasSize(matrix.rows() * 9);
        assertThat(matrix.rowWeights()).hasSize(matrix.rows());
    }

    @Test
    void fitsPoliciesAndScenariosOnlyOnceAndIgnoresHeldOutOutliers() {
        PolicyVector first = policies(3).get(0);
        PolicyVector second = policies(3).get(1);
        SourceScenario low = SourceScenario.of("a", 1, 4);
        SourceScenario high = SourceScenario.of("b", 4, 4);
        List<ScenarioLearningRow> training = List.of(
                row(first, low), row(first, high), row(second, low));
        FeatureNormalizer fitted =
                FeatureNormalizer.fit(training, ScenarioFeatureSet.RATIO_ONLY);
        assertThat(fitted.means()[0]).isEqualTo(
                (first.weight(0) + second.weight(0)) / 2);
        assertThat(fitted.means()[28]).isEqualTo((0.25 + 1.0) / 2);
        PolicyVector outlier = PolicyVector.of(java.util.stream.DoubleStream
                .generate(() -> 100.0).limit(28).toArray());
        float[] heldOut = new float[29];
        fitted.encode(outlier, SourceScenario.of("c", 1_000, 1), heldOut, 0);
        assertThat(fitted.means()[0]).isEqualTo(
                (first.weight(0) + second.weight(0)) / 2);
        assertThat(fitted.means()[28]).isEqualTo((0.25 + 1.0) / 2);
    }

    @Test
    void ratioIgnoresEnvironmentWhileCountsDistinguishAbsoluteCounts() {
        PolicyVector policy = policies(2).getFirst();
        SourceScenario small = SourceScenario.of("a", 1, 4);
        SourceScenario sameRatioOtherEnvironment = SourceScenario.of("b", 2, 8);
        List<ScenarioLearningRow> rows =
                List.of(row(policy, small), row(policy, sameRatioOtherEnvironment));
        FeatureNormalizer ratio = FeatureNormalizer.fit(rows, ScenarioFeatureSet.RATIO_ONLY);
        float[] first = new float[29], second = new float[29];
        ratio.encode(policy, small, first, 0);
        ratio.encode(policy, sameRatioOtherEnvironment, second, 0);
        assertThat(first).containsExactly(second);

        FeatureNormalizer counts =
                FeatureNormalizer.fit(rows, ScenarioFeatureSet.RATIO_AND_COUNTS);
        float[] firstCounts = new float[31], secondCounts = new float[31];
        counts.encode(policy, small, firstCounts, 0);
        counts.encode(policy, sameRatioOtherEnvironment, secondCounts, 0);
        assertThat(Arrays.copyOf(firstCounts, 29))
                .containsExactly(Arrays.copyOf(secondCounts, 29));
        assertThat(firstCounts[29]).isNotEqualTo(secondCounts[29]);
        assertThat(firstCounts[30]).isNotEqualTo(secondCounts[30]);
    }

    @Test
    void constantFeaturesUseUnitScaleAndPositiveZeroWithoutMutatingPolicy() {
        PolicyVector policy = policies(2).getFirst();
        double[] before = policy.copyWeights();
        SourceScenario scenario = SourceScenario.of("a", 1, 4);
        FeatureNormalizer normalizer = FeatureNormalizer.fit(
                List.of(row(policy, scenario)), ScenarioFeatureSet.RATIO_ONLY);
        assertThat(normalizer.scales()).containsOnly(1.0);
        assertThat(normalizer.constantFeatures()).containsOnly(true);
        float[] encoded = new float[29];
        normalizer.encode(policy, scenario, encoded, 0);
        assertThat(encoded).containsOnly(0.0f);
        for (float value : encoded) {
            assertThat(Float.floatToRawIntBits(value)).isZero();
        }
        assertThat(policy.copyWeights()).containsExactly(before);
    }

    @Test
    void givesEveryExactScenarioEqualTotalRowWeight() {
        List<ScenarioLearningRow> rows = learningRows();
        FeatureNormalizer normalizer =
                FeatureNormalizer.fit(rows, ScenarioFeatureSet.RATIO_ONLY);
        ScenarioLearningMatrix matrix =
                ScenarioFeatureEncoder.matrix(rows, scenarios(), normalizer);
        float[] weights = matrix.rowWeights();
        SourceScenario[] matrixScenarios = matrix.scenarios();
        for (SourceScenario scenario : scenarios()) {
            double total = 0;
            for (int row = 0; row < matrix.rows(); row++) {
                if (matrixScenarios[row].equals(scenario)) total += weights[row];
            }
            assertThat(total).isEqualTo(160.0);
        }
    }

    @Test
    void encodesExactRatioLogCountsAndUnmodifiedPolicyCoordinates() {
        double[] weights = new double[28];
        weights[0] = 3;
        weights[1] = 4;
        weights[2] = 100;
        PolicyVector policy = PolicyVector.of(weights);
        SourceScenario scenario = SourceScenario.of("host", 8, 32);
        FeatureNormalizer identity = new FeatureNormalizer(
                ScenarioFeatureSet.RATIO_AND_COUNTS.schemaId(),
                ScenarioFeatureSet.RATIO_AND_COUNTS.featureNames(),
                new double[31], java.util.stream.DoubleStream.generate(() -> 1.0)
                .limit(31).toArray(), new boolean[31]);
        float[] encoded = new float[31];
        identity.encode(policy, scenario, encoded, 0);
        assertThat(encoded[0]).isEqualTo(3.0f);
        assertThat(encoded[1]).isEqualTo(4.0f);
        assertThat(encoded[2]).isEqualTo(100.0f);
        assertThat(encoded[28]).isEqualTo((float) scenario.ratio().asDouble());
        assertThat(encoded[29]).isEqualTo((float) StrictMath.log1p(8));
        assertThat(encoded[30]).isEqualTo((float) StrictMath.log1p(32));
        assertThat(policy.copyWeights()).containsExactly(weights);
    }

    private static ScenarioLearningRow row(PolicyVector policy, SourceScenario scenario) {
        return new ScenarioLearningRow(policy, scenario, ScenarioResultStatus.VALID_STRONG,
                0.5, 10, 9, 11, 1, 0.1, 0);
    }
}
