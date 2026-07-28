package io.euhedral_execution.training.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.euhedral_execution.training.data.*;
import io.euhedral_execution.training.merge.MergeRecords.ScenarioResultStatus;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioConditionedModelTest {
    @TempDir Path temporary;
    @Test void sourceContextChangesPrediction() {
        PolicyVector policy = PolicyVector.of(new double[28]);
        SourceScenario low = SourceScenario.of("a", 1, 4);
        SourceScenario high = SourceScenario.of("a", 4, 4);
        List<ScenarioLearningRow> rows = List.of(row(policy, low), row(policy, high));
        FeatureNormalizer normalizer = FeatureNormalizer.fit(rows, ScenarioFeatureSet.RATIO_ONLY);
        try (ScenarioConditionedModel model = ScenarioConditionedModel.forTest(normalizer,
                new TreeSet<>(List.of(low, high)), List.of(new RatioMember()))) {
            var curve = model.predictConfiguredCurves(List.of(policy)).getFirst();
            assertThat(curve.scenarios().get(0).predictedQuality())
                    .isNotEqualTo(curve.scenarios().get(1).predictedQuality());
        }
    }

    @Test
    void policyOnlyPredictionsAreScenarioInvariantAndOrderingIsStable() {
        List<PolicyVector> policies = List.of(
                PolicyVector.of(new double[28]),
                policyWithFirstWeight(1));
        SourceScenario first = SourceScenario.of("b", 4, 4);
        SourceScenario second = SourceScenario.of("a", 1, 4);
        List<ScenarioLearningRow> rows = List.of(row(policies.get(0), first),
                row(policies.get(0), second), row(policies.get(1), first),
                row(policies.get(1), second));
        FeatureNormalizer normalizer =
                FeatureNormalizer.fit(rows, ScenarioFeatureSet.POLICY_ONLY);
        try (ScenarioConditionedModel model = ScenarioConditionedModel.forTest(normalizer,
                new TreeSet<>(List.of(first, second)), List.of(new PolicyMember()))) {
            List<PolicyPredictionCurve> curves =
                    model.predictConfiguredCurves(policies.reversed());
            assertThat(curves).extracting(curve -> curve.policy().id())
                    .containsExactly(policies.get(1).id(), policies.get(0).id());
            assertThat(curves).allSatisfy(curve -> {
                assertThat(curve.scenarios()).extracting(ScenarioPrediction::scenario)
                        .isSorted();
                assertThat(curve.scenarios().get(0).predictedQuality())
                        .isEqualTo(curve.scenarios().get(1).predictedQuality());
            });
        }
    }

    @Test
    void batchingDoesNotSplitCurvesOrChangeAggregation() {
        List<PolicyVector> policies = List.of(policyWithFirstWeight(0),
                policyWithFirstWeight(1), policyWithFirstWeight(2));
        SourceScenario low = SourceScenario.of("a", 1, 4);
        SourceScenario high = SourceScenario.of("a", 4, 4);
        List<ScenarioLearningRow> rows = new ArrayList<>();
        for (PolicyVector policy : policies) {
            rows.add(row(policy, low));
            rows.add(row(policy, high));
        }
        FeatureNormalizer normalizer =
                FeatureNormalizer.fit(rows, ScenarioFeatureSet.RATIO_ONLY);
        TreeSet<SourceScenario> scenarios = new TreeSet<>(List.of(low, high));
        try (ScenarioConditionedModel model = ScenarioConditionedModel.forTest(
                normalizer, scenarios, List.of(new RatioMember(), new OffsetMember()))) {
            assertThat(model.predictCurves(policies, scenarios, 100))
                    .isEqualTo(model.predictCurves(policies, scenarios, 2));
        }
    }

    @Test
    void invalidInputsFailAndMemberFailureClosesAllMembersOnce() {
        PolicyVector policy = PolicyVector.of(new double[28]);
        SourceScenario scenario = SourceScenario.of("a", 1, 4);
        FeatureNormalizer normalizer = FeatureNormalizer.fit(
                List.of(row(policy, scenario)), ScenarioFeatureSet.RATIO_ONLY);
        CountingMember first = new CountingMember(false);
        CountingMember failing = new CountingMember(true);
        CountingMember last = new CountingMember(false);
        ScenarioConditionedModel model = ScenarioConditionedModel.forTest(normalizer,
                new TreeSet<>(List.of(scenario)), List.of(first, failing, last));
        assertThatThrownBy(() -> model.predictConfiguredCurves(List.of(policy, policy)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.predictCurves(List.of(policy), new TreeSet<>(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.predictCurves(List.of(policy),
                new TreeSet<>(List.of(scenario)), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.predictConfiguredCurves(List.of(policy)))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(first.closes).isOne();
        assertThat(failing.closes).isOne();
        assertThat(last.closes).isOne();
        assertThatThrownBy(() -> model.predictConfiguredCurves(List.of(policy)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Model is closed");
        model.close();
        assertThat(first.closes).isOne();
    }

    @Test
    void strictLoadRejectsMetadataLessPooledAndChecksumMismatchedArtifacts() throws Exception {
        assertThatThrownBy(() -> ScenarioConditionedModel.load(temporary))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("pooled 28-input artifact")
                .hasMessageContaining("retrained");
        ScenarioModelMetadata metadata = ScenarioModelMetadataCodecTest.metadata();
        Path rejectedDirectory = temporary.resolve("rejected");
        Files.createDirectories(rejectedDirectory);
        ScenarioModelMetadataCodec.write(rejectedDirectory.resolve(
                ScenarioModelMetadataCodec.FILE_NAME), rejected(metadata));
        assertThatThrownBy(() -> ScenarioConditionedModel.load(rejectedDirectory))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("rejected");

        Path missingDirectory = temporary.resolve("missing");
        Files.createDirectories(missingDirectory);
        ScenarioModelMetadataCodec.write(missingDirectory.resolve(
                ScenarioModelMetadataCodec.FILE_NAME), metadata);
        assertThatThrownBy(() -> ScenarioConditionedModel.load(missingDirectory))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Missing or checksum-mismatched");

        Path changedDirectory = temporary.resolve("changed");
        Files.createDirectories(changedDirectory);
        ScenarioModelMetadataCodec.write(
                changedDirectory.resolve(ScenarioModelMetadataCodec.FILE_NAME), metadata);
        for (MemberMetadata member : metadata.members()) {
            Path file = changedDirectory.resolve(member.relativePath());
            Files.createDirectories(file.getParent());
            Files.writeString(file, "tampered");
        }
        assertThatThrownBy(() -> ScenarioConditionedModel.load(changedDirectory))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum-mismatched");
    }

    private static ScenarioModelMetadata rejected(ScenarioModelMetadata source) {
        return new ScenarioModelMetadata(source.schemaVersion(), source.objectiveVersion(),
                source.featureSet(), source.normalizer(), source.ordinalThresholdBits(),
                source.architecture(), source.memberModelName(), source.members(),
                source.splitAlgorithm(), source.splitSeed(), source.modelSeed(),
                source.datasetFingerprintSha256(), source.includeWeakCalibrationRows(),
                source.requiredScenarios(), source.trainingScenarios(),
                source.partitionCounts(), source.trainingConfig(), source.featureSelection(),
                source.evaluationSummary(),
                ModelAcceptanceStatus.SCENARIO_CONTEXT_GATE_FAILED,
                List.of("SCENARIO_CONTEXT_GATE_FAILED"), source.producer(),
                source.metadataProbe());
    }
    private static ScenarioLearningRow row(PolicyVector p, SourceScenario s) {
        return new ScenarioLearningRow(p, s, ScenarioResultStatus.VALID_STRONG, .5, 10, 9, 11,
                1, .1, 0);
    }

    private static PolicyVector policyWithFirstWeight(double value) {
        double[] weights = new double[28];
        weights[0] = value;
        return PolicyVector.of(weights);
    }
    private static final class RatioMember implements OrdinalMember {
        public int featureWidth() { return 29; }
        public void predictLogits(float[] features, int rows, float[] out) {
            for (int row = 0; row < rows; row++) {
                Arrays.fill(out, row * 9, row * 9 + 9, features[row * 29 + 28]);
            }
        }
        public void close() {}
    }

    private static final class PolicyMember implements OrdinalMember {
        public int featureWidth() { return 28; }
        public void predictLogits(float[] features, int rows, float[] out) {
            for (int row = 0; row < rows; row++) {
                Arrays.fill(out, row * 9, row * 9 + 9, features[row * 28]);
            }
        }
        public void close() {}
    }

    private static final class OffsetMember implements OrdinalMember {
        public int featureWidth() { return 29; }
        public void predictLogits(float[] features, int rows, float[] out) {
            for (int row = 0; row < rows; row++) {
                Arrays.fill(out, row * 9, row * 9 + 9, features[row * 29 + 28] + 0.5f);
            }
        }
        public void close() {}
    }

    private static final class CountingMember implements OrdinalMember {
        private final boolean fail;
        private int closes;
        private CountingMember(boolean fail) { this.fail = fail; }
        public int featureWidth() { return 29; }
        public void predictLogits(float[] features, int rows, float[] out) {
            if (fail) throw new IllegalStateException("boom");
        }
        public void close() { closes++; }
    }
}
