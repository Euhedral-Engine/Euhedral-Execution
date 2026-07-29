package io.euhedral_execution.training.learning;

import io.euhedral_execution.training.data.PartitionCounts;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.ScenarioMemberSeeds;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.enums.EvaluationStatus;
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
import io.euhedral_execution.training.learning.output.EvaluationSummary;
import io.euhedral_execution.training.learning.output.ScenarioLearningReportWriter;
import io.euhedral_execution.training.learning.output.ScenarioTrainingArtifacts;
import io.euhedral_execution.training.learning.output.TrainingHistoryEntry;
import io.euhedral_execution.training.learning.statistics.AblationMetric;
import io.euhedral_execution.training.learning.statistics.LosoEvaluationMetrics;
import io.euhedral_execution.training.learning.statistics.ScenarioEvaluationMetrics;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic Phase 6 model artifact that exercises metadata and scheduling without loading DJL.
 */
public final class AuditScenarioModelFixture {
    private static final ScenarioFeatureSet FEATURES = ScenarioFeatureSet.RATIO_ONLY;

    public static ScenarioTrainingArtifacts write(Path modelDirectory,
            SortedSet<SourceScenario> requiredScenarios, ScenarioTrainingConfig trainingConfig,
            PolicyVector probePolicy, String commitSha, boolean dirtyWorkingTree)
            throws Exception {
        return write(modelDirectory, requiredScenarios, trainingConfig, probePolicy,
                commitSha, dirtyWorkingTree, ModelAcceptanceStatus.ACCEPTED, List.of());
    }

    public static ScenarioTrainingArtifacts writeRejected(Path modelDirectory,
            SortedSet<SourceScenario> requiredScenarios, ScenarioTrainingConfig trainingConfig,
            PolicyVector probePolicy, String commitSha, boolean dirtyWorkingTree)
            throws Exception {
        return write(modelDirectory, requiredScenarios, trainingConfig, probePolicy,
                commitSha, dirtyWorkingTree,
                ModelAcceptanceStatus.SCENARIO_CONTEXT_GATE_FAILED,
                List.of("AUDIT_FORCED_REJECTION"));
    }

    private static ScenarioTrainingArtifacts write(Path modelDirectory,
            SortedSet<SourceScenario> requiredScenarios, ScenarioTrainingConfig trainingConfig,
            PolicyVector probePolicy, String commitSha, boolean dirtyWorkingTree,
            ModelAcceptanceStatus acceptanceStatus, List<String> acceptanceReasons)
            throws Exception {
        Files.createDirectories(modelDirectory);
        List<ScenarioLearningRow> rows = requiredScenarios.stream().map(scenario ->
                new ScenarioLearningRow(probePolicy, scenario,
                        ScenarioResultStatus.VALID_STRONG, 0.5, 90, 90, 90,
                        1, 0, 0)).toList();
        FeatureNormalizer normalizer = FeatureNormalizer.fit(rows, FEATURES);
        AblationMetric contextGate = contextGate();

        List<MemberMetadata> members = new ArrayList<>();
        List<TrainingHistoryEntry> history = new ArrayList<>();
        for (int index = 0; index < trainingConfig.ensembleMembers(); index++) {
            long seed = ScenarioMemberSeeds.derive(trainingConfig.modelSeed(),
                    "PRODUCTION", FEATURES, "all", index);
            Path member = modelDirectory.resolve(MemberMetadata.expectedPath(index));
            Files.createDirectories(member.getParent());
            Files.writeString(member, "phase6-audit-member-%d\n".formatted(index),
                    StandardCharsets.US_ASCII);
            members.add(new MemberMetadata(index, seed, 1,
                    MemberMetadata.expectedPath(index),
                    ScenarioConditionedModel.sha256(member)));
            history.add(new TrainingHistoryEntry("PRODUCTION", "all", FEATURES,
                    index, seed, 1, 0.1, OptionalDouble.of(0.8), 0.1, true));
        }

        List<ScenarioEvaluationMetrics> groupedRows = requiredScenarios.stream()
                .map(scenario -> metrics("GROUPED_TEST", "all", scenario)).toList();
        EvaluationSummary grouped = new EvaluationSummary("GROUPED_TEST", FEATURES,
                groupedRows, OptionalDouble.of(0.1), OptionalDouble.of(0.1),
                OptionalDouble.of(0.8), OptionalDouble.of(0.5),
                OptionalDouble.of(0.5), OptionalDouble.of(0.1),
                OptionalDouble.of(0.1));
        List<LosoEvaluationMetrics> loso = requiredScenarios.stream().map(scenario ->
                new LosoEvaluationMetrics(metrics("LOSO_TEST", scenario.canonical(), scenario),
                        scenario.ratio().asDouble(), false,
                        requiredScenarios.size() - 1,
                        (int) requiredScenarios.stream().filter(other ->
                                !other.equals(scenario)).map(SourceScenario::ratio)
                                .distinct().count())).toList();

        Path groupedPath = modelDirectory.resolve("grouped-evaluation.csv");
        Path losoPath = modelDirectory.resolve("loso-evaluation.csv");
        Path ablationPath = modelDirectory.resolve("ablation-evaluation.csv");
        Path historyPath = modelDirectory.resolve("training-history.csv");
        ScenarioLearningReportWriter.writeGrouped(groupedPath, grouped);
        ScenarioLearningReportWriter.writeLoso(losoPath, loso);
        ScenarioLearningReportWriter.writeAblation(ablationPath, List.of(contextGate));
        ScenarioLearningReportWriter.writeHistory(historyPath, history);

        EvaluationSummaryMetadata evaluation = new EvaluationSummaryMetadata(
                groupedPath.getFileName().toString(), losoPath.getFileName().toString(),
                OptionalDouble.of(0.1), OptionalDouble.of(0.8),
                OptionalDouble.of(0.5), OptionalDouble.of(0.1),
                OptionalDouble.of(0.8), OptionalDouble.of(0.1));
        FeatureSelectionDecision selection = new FeatureSelectionDecision(
                FeatureSelectionMode.RATIO_ONLY, FEATURES, List.of(contextGate),
                "RATIO_ONLY_REQUESTED");
        MetadataProbe probe = new MetadataProbe(probePolicy.id(),
                requiredScenarios.first(), List.of("0000000000000000",
                "0000000000000000", "0000000000000000", "0000000000000000",
                "0000000000000000", "0000000000000000", "0000000000000000",
                "0000000000000000"), "cpu");
        ScenarioModelMetadata metadata = new ScenarioModelMetadata(
                ScenarioModelMetadata.SCHEMA_VERSION,
                ScenarioModelMetadata.OBJECTIVE_VERSION, FEATURES, normalizer,
                ScenarioModelMetadata.expectedThresholdBits(),
                ScenarioModelMetadata.ARCHITECTURE,
                ScenarioModelMetadata.MEMBER_MODEL_NAME, members,
                ScenarioModelMetadata.SPLIT_ALGORITHM, trainingConfig.splitSeed(),
                trainingConfig.modelSeed(), "a".repeat(64),
                trainingConfig.includeWeakCalibrationRows(), requiredScenarios,
                requiredScenarios, partitionCounts(requiredScenarios),
                trainingConfig, selection, evaluation, acceptanceStatus,
                acceptanceReasons, new ProducerMetadata(commitSha, dirtyWorkingTree,
                "PyTorch", "2.7.1", "cpu"), probe);
        Path metadataPath = modelDirectory.resolve(ScenarioModelMetadataCodec.FILE_NAME);
        ScenarioModelMetadataCodec.write(metadataPath, metadata);
        ScenarioModelMetadata reopened = ScenarioModelMetadataCodec.read(metadataPath);
        if (!ScenarioModelMetadataCodec.encode(reopened)
                .equals(ScenarioModelMetadataCodec.encode(metadata))) {
            throw new IllegalStateException("Audit model metadata did not round trip");
        }
        return new ScenarioTrainingArtifacts(modelDirectory, metadataPath, groupedPath,
                losoPath, ablationPath, historyPath, acceptanceStatus,
                FEATURES);
    }

    public static ScenarioConditionedModel open(Path modelDirectory) throws Exception {
        ScenarioModelMetadata metadata = ScenarioModelMetadataCodec.read(
                modelDirectory.resolve(ScenarioModelMetadataCodec.FILE_NAME));
        List<OrdinalMember> members = metadata.members().stream()
                .map(ignored -> (OrdinalMember) new ConstantMember(FEATURES.width()))
                .toList();
        return ScenarioConditionedModel.forTest(metadata, members);
    }

    private static PartitionCounts partitionCounts(
            SortedSet<SourceScenario> scenarios) {
        TreeMap<String, Integer> policies = new TreeMap<>();
        TreeMap<String, Integer> rows = new TreeMap<>();
        TreeMap<String, SortedMap<SourceScenario, Integer>> byScenario =
                new TreeMap<>();
        for (String partition : List.of("TRAIN", "VALIDATION", "TEST",
                "ABLATION_EARLY_STOP", "ABLATION_SCORE")) {
            int policiesInPartition = partition.equals("VALIDATION") ? 2 : 1;
            int rowsPerScenario = partition.equals("VALIDATION") ? 2 : 1;
            policies.put(partition, policiesInPartition);
            rows.put(partition, Math.multiplyExact(rowsPerScenario, scenarios.size()));
            TreeMap<SourceScenario, Integer> counts = new TreeMap<>();
            scenarios.forEach(scenario -> counts.put(scenario, rowsPerScenario));
            byScenario.put(partition, counts);
        }
        return new PartitionCounts(policies, rows, byScenario);
    }

    private static ScenarioEvaluationMetrics metrics(String kind, String fold,
            SourceScenario scenario) {
        return new ScenarioEvaluationMetrics(kind, fold, FEATURES, scenario,
                1, 1, 0.1, 0.1, 0, OptionalDouble.of(0.8),
                1, 1, OptionalDouble.of(1), OptionalDouble.of(1),
                0.1, 1, 0, 0, EvaluationStatus.OK);
    }

    private static AblationMetric contextGate() {
        return new AblationMetric("VALIDATION_CONTEXT_GATE", "all",
                FEATURES, ScenarioFeatureSet.POLICY_ONLY, "all", 4,
                OptionalDouble.of(0.1), OptionalDouble.of(0.8),
                OptionalDouble.of(-0.1), OptionalDouble.of(0.1), true,
                "PASS", "CONTEXT_VALIDATED");
    }

    private static final class ConstantMember implements OrdinalMember {
        private final int featureWidth;

        private ConstantMember(int featureWidth) {
            this.featureWidth = featureWidth;
        }

        @Override
        public int featureWidth() {
            return featureWidth;
        }

        @Override
        public void predictLogits(float[] features, int rows, float[] destination) {
            Arrays.fill(destination, 0, Math.multiplyExact(rows, 9), 0.0f);
        }

        @Override
        public void close() {
        }
    }

    private AuditScenarioModelFixture() {
    }
}
