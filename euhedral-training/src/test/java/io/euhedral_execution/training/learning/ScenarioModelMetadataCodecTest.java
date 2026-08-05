package io.euhedral_execution.training.learning;

import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.scenarios;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.data.PartitionCounts;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.ScenarioMemberSeeds;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.enums.FeatureSelectionMode;
import io.euhedral_execution.training.learning.enums.ModelAcceptanceStatus;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningRow;
import io.euhedral_execution.training.learning.metadata.EvaluationSummaryMetadata;
import io.euhedral_execution.training.learning.metadata.FeatureNormalizer;
import io.euhedral_execution.training.learning.metadata.FeatureSelectionDecision;
import io.euhedral_execution.training.learning.metadata.MemberMetadata;
import io.euhedral_execution.training.learning.metadata.MetadataProbe;
import io.euhedral_execution.training.learning.metadata.ProducerMetadata;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadata;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadataCodec;
import io.euhedral_execution.training.learning.statistics.AblationMetric;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ScenarioModelMetadataCodecTest {
    @Test
    void roundTripsCanonicalBytesAndEveryRawNormalizerBit() throws Exception {
        ScenarioModelMetadata metadata = metadata();
        String encoded = ScenarioModelMetadataCodec.encode(metadata);
        ScenarioModelMetadata decoded = ScenarioModelMetadataCodec.decode(encoded);
        assertThat(ScenarioModelMetadataCodec.encode(decoded)).isEqualTo(encoded);
        assertThat(decoded.normalizer().means())
                .containsExactly(metadata.normalizer().means());
        assertThat(decoded.normalizer().scales())
                .containsExactly(metadata.normalizer().scales());
        assertThat(encoded).endsWith("\n");
        assertThat(encoded).contains("\"artifact_type\": " + "\"euhedral-scenario-conditioned-ordinal-model\"");
    }

    @Test
    void goldenMetadataDecodesAndCanonicalizesByteStably() throws Exception {
        String golden;
        try (InputStream input = getClass().getResourceAsStream("/robust-training/v1/scenario-model-metadata.json")) {
            assertThat(input).isNotNull();
            golden = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        ScenarioModelMetadata decoded = ScenarioModelMetadataCodec.decode(golden);
        String canonical = ScenarioModelMetadataCodec.encode(decoded);
        assertThat(canonical).isEqualTo(golden);
        assertThat(ScenarioModelMetadataCodec.encode(ScenarioModelMetadataCodec.decode(canonical)))
                .isEqualTo(canonical);
    }

    @Test
    void rejectsUnknownDuplicateMissingAndTrailingFields() {
        String encoded = ScenarioModelMetadataCodec.encode(metadata());
        assertThatThrownBy(() -> ScenarioModelMetadataCodec.decode(encoded.replaceFirst("\\{", "{\"unknown\": 1,")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ScenarioModelMetadataCodec.decode(
                        encoded.replaceFirst("\"schema_version\": 1,", "\"schema_version\": 1,\"schema_version\": 1,")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ScenarioModelMetadataCodec.decode(encoded.replaceFirst("\"policy_width\": 28,", "")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ScenarioModelMetadataCodec.decode(encoded + "x"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ScenarioModelMetadataCodec.decode("{\"x\":\"\\\\q\"}"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsChangedObjectiveFeatureNamesAndThresholds() {
        String encoded = ScenarioModelMetadataCodec.encode(metadata());
        assertThatThrownBy(() -> ScenarioModelMetadataCodec.decode(
                        encoded.replace("scenario-quality-ordinal-v1", "scenario-quality-ordinal-v2")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ScenarioModelMetadataCodec.decode(
                        encoded.replace("\"schema_version\": 1", "\"schema_version\": 2")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ScenarioModelMetadataCodec.decode(
                        encoded.replace("\"feature_width\": 29", "\"feature_width\": 28")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() ->
                        ScenarioModelMetadataCodec.decode(encoded.replace("policy_weight_00", "policy_weight_xx")))
                .isInstanceOf(IOException.class);
        String threshold = ScenarioModelMetadata.expectedThresholdBits().getFirst();
        assertThatThrownBy(() -> ScenarioModelMetadataCodec.decode(encoded.replace(threshold, "0000000000000000")))
                .isInstanceOf(IOException.class);
    }

    static ScenarioModelMetadata metadata() {
        PolicyVector policy = PolicyVector.of(new double[28]);
        SourceScenario scenario = scenarios().first();
        ScenarioLearningRow row =
                new ScenarioLearningRow(policy, scenario, ScenarioResultStatus.VALID_STRONG, 0.5, 10, 9, 11, 1, 0.1, 0);
        FeatureNormalizer normalizer = FeatureNormalizer.fit(List.of(row), ScenarioFeatureSet.RATIO_ONLY);
        ScenarioTrainingConfig config = ScenarioTrainingConfig.forTest(1, 2, FeatureSelectionMode.RATIO_ONLY);
        TreeMap<String, Integer> policyCounts = new TreeMap<>();
        TreeMap<String, Integer> rowCounts = new TreeMap<>();
        TreeMap<String, java.util.SortedMap<SourceScenario, Integer>> scenarioCounts = new TreeMap<>();
        for (String partition : List.of("TRAIN", "VALIDATION", "TEST", "ABLATION_EARLY_STOP", "ABLATION_SCORE")) {
            int count = partition.equals("VALIDATION") ? 2 : 1;
            policyCounts.put(partition, count);
            rowCounts.put(partition, count);
            scenarioCounts.put(partition, new TreeMap<>(Map.of(scenario, count)));
        }
        PartitionCounts partitions = new PartitionCounts(policyCounts, rowCounts, scenarioCounts);
        FeatureSelectionDecision selection = new FeatureSelectionDecision(
                FeatureSelectionMode.RATIO_ONLY,
                ScenarioFeatureSet.RATIO_ONLY,
                List.of(new AblationMetric(
                        "VALIDATION_CONTEXT_GATE",
                        "all",
                        ScenarioFeatureSet.RATIO_ONLY,
                        ScenarioFeatureSet.POLICY_ONLY,
                        "all",
                        10,
                        OptionalDouble.of(0.19),
                        OptionalDouble.of(0.48),
                        OptionalDouble.of(-0.01),
                        OptionalDouble.of(-0.02),
                        true,
                        "PASS",
                        "CONTEXT_VALIDATED")),
                "RATIO_ONLY_REQUESTED");
        EvaluationSummaryMetadata summary = new EvaluationSummaryMetadata(
                "grouped-evaluation.csv",
                "loso-evaluation.csv",
                OptionalDouble.of(0.1),
                OptionalDouble.of(0.8),
                OptionalDouble.of(0.5),
                OptionalDouble.of(0.1),
                OptionalDouble.of(0.8),
                OptionalDouble.of(0.1));
        List<MemberMetadata> members = java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> member(
                        index,
                        ScenarioMemberSeeds.derive(
                                config.modelSeed(), "PRODUCTION", ScenarioFeatureSet.RATIO_ONLY, "all", index)))
                .toList();
        MetadataProbe probe = new MetadataProbe(
                policy.id(),
                scenario,
                List.of(
                        "0000000000000000", "0000000000000000",
                        "0000000000000000", "0000000000000000",
                        "0000000000000000", "0000000000000000",
                        "0000000000000000", "0000000000000000"),
                "cpu");
        return new ScenarioModelMetadata(
                1,
                ScenarioModelMetadata.OBJECTIVE_VERSION,
                ScenarioFeatureSet.RATIO_ONLY,
                normalizer,
                ScenarioModelMetadata.expectedThresholdBits(),
                ScenarioModelMetadata.ARCHITECTURE,
                ScenarioModelMetadata.MEMBER_MODEL_NAME,
                members,
                ScenarioModelMetadata.SPLIT_ALGORITHM,
                1,
                2,
                "a".repeat(64),
                false,
                new TreeSet<>(List.of(scenario)),
                new TreeSet<>(List.of(scenario)),
                partitions,
                config,
                selection,
                summary,
                ModelAcceptanceStatus.ACCEPTED,
                List.of(),
                new ProducerMetadata("0".repeat(40), false, "TensorFlow", "1.2.0", "cpu"),
                probe);
    }

    private static MemberMetadata member(int index, long seed) {
        return new MemberMetadata(
                index,
                seed,
                1,
                MemberMetadata.expectedPath(index),
                Integer.toHexString(index).repeat(64).substring(0, 64));
    }
}
