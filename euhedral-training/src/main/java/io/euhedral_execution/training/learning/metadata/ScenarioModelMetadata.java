package io.euhedral_execution.training.learning.metadata;

import io.euhedral_execution.training.data.PartitionCounts;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.EvaluationThresholds;
import io.euhedral_execution.training.learning.config.ScenarioMemberSeeds;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.enums.FeatureSelectionMode;
import io.euhedral_execution.training.learning.enums.ModelAcceptanceStatus;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.utils.ScenarioOrdinalTargets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

public record ScenarioModelMetadata(
        int schemaVersion,
        String objectiveVersion,
        ScenarioFeatureSet featureSet,
        FeatureNormalizer normalizer,
        List<String> ordinalThresholdBits,
        String architecture,
        String memberModelName,
        List<MemberMetadata> members,
        String splitAlgorithm,
        long splitSeed,
        long modelSeed,
        String datasetFingerprintSha256,
        boolean includeWeakCalibrationRows,
        SortedSet<SourceScenario> requiredScenarios,
        SortedSet<SourceScenario> trainingScenarios,
        PartitionCounts partitionCounts,
        ScenarioTrainingConfig trainingConfig,
        FeatureSelectionDecision featureSelection,
        EvaluationSummaryMetadata evaluationSummary,
        ModelAcceptanceStatus acceptanceStatus,
        List<String> acceptanceReasons,
        ProducerMetadata producer,
        MetadataProbe metadataProbe) {

    public static final String ARTIFACT_TYPE = "euhedral-scenario-conditioned-ordinal-model";
    public static final int SCHEMA_VERSION = 1;
    public static final String OBJECTIVE_VERSION = "scenario-quality-ordinal-v1";
    public static final int LEARNING_SCHEMA_VERSION = 1;
    public static final String POLICY_ID_SCHEME = "p1";
    public static final int POLICY_WIDTH = 28;
    public static final int OUTPUT_WIDTH = 9;
    public static final String ARCHITECTURE = "F-128-96-48-9-gelu";
    public static final String MEMBER_MODEL_NAME = "euhedral-scenario-ordinal";
    public static final String SPLIT_ALGORITHM = "policy-hash-80-10-10-v1";

    public ScenarioModelMetadata {
        Objects.requireNonNull(objectiveVersion);
        Objects.requireNonNull(featureSet);
        Objects.requireNonNull(normalizer);
        Objects.requireNonNull(ordinalThresholdBits);
        Objects.requireNonNull(architecture);
        Objects.requireNonNull(memberModelName);
        Objects.requireNonNull(members);
        Objects.requireNonNull(splitAlgorithm);
        Objects.requireNonNull(datasetFingerprintSha256);
        Objects.requireNonNull(requiredScenarios);
        Objects.requireNonNull(trainingScenarios);
        Objects.requireNonNull(partitionCounts);
        Objects.requireNonNull(trainingConfig);
        Objects.requireNonNull(featureSelection);
        Objects.requireNonNull(evaluationSummary);
        Objects.requireNonNull(acceptanceStatus);
        Objects.requireNonNull(acceptanceReasons);
        Objects.requireNonNull(producer);
        Objects.requireNonNull(metadataProbe);
        ordinalThresholdBits = List.copyOf(ordinalThresholdBits);
        members = members.stream().sorted().toList();
        requiredScenarios = Collections.unmodifiableSortedSet(new TreeSet<>(requiredScenarios));
        trainingScenarios = Collections.unmodifiableSortedSet(new TreeSet<>(trainingScenarios));
        acceptanceReasons = List.copyOf(acceptanceReasons);
        if (acceptanceReasons.stream().distinct().count() != acceptanceReasons.size()) {
            throw new IllegalArgumentException("Duplicate acceptance reason");
        }
        if (schemaVersion != SCHEMA_VERSION
                || !objectiveVersion.equals(OBJECTIVE_VERSION)
                || !architecture.equals(ARCHITECTURE)
                || !memberModelName.equals(MEMBER_MODEL_NAME)
                || !splitAlgorithm.equals(SPLIT_ALGORITHM)
                || !datasetFingerprintSha256.matches("[0-9a-f]{64}")
                || requiredScenarios.isEmpty()
                || trainingScenarios.isEmpty()
                || !requiredScenarios.containsAll(trainingScenarios)
                || !featureSet.schemaId().equals(normalizer.featureSchemaId())
                || !featureSet.featureNames().equals(normalizer.featureNames())
                || !ordinalThresholdBits.equals(expectedThresholdBits())
                || featureSelection.selectedFeatureSet() != featureSet
                || featureSelection.requestedMode() != trainingConfig.featureSelectionMode()
                || trainingConfig.includeWeakCalibrationRows() != includeWeakCalibrationRows
                || trainingConfig.splitSeed() != splitSeed
                || trainingConfig.modelSeed() != modelSeed
                || members.size() != trainingConfig.ensembleMembers()
                || !requiredScenarios.contains(metadataProbe.scenario())
                || !producer.trainingDevice().equals(metadataProbe.producingDevice())
                || members.isEmpty()) {
            throw new IllegalArgumentException("Invalid scenario model metadata");
        }
        for (var counts : partitionCounts.scenarioRowCounts().values()) {
            if (!counts.keySet().equals(requiredScenarios)) {
                throw new IllegalArgumentException("Partition scenarios disagree with required scenarios");
            }
        }
        for (int index = 0; index < members.size(); index++) {
            MemberMetadata member = members.get(index);
            if (member.index() != index
                    || member.seed() != ScenarioMemberSeeds.derive(modelSeed, "PRODUCTION", featureSet, "all", index)
                    || member.bestEpoch() >= trainingConfig.maxEpochs()) {
                throw new IllegalArgumentException("Member indexes must be contiguous");
            }
        }
        if (acceptanceStatus == ModelAcceptanceStatus.ACCEPTED
                && (!acceptanceReasons.isEmpty()
                        || !trainingScenarios.equals(requiredScenarios)
                        || featureSet.ablationOnly()
                        || !acceptedEvaluationPasses(evaluationSummary, trainingConfig.thresholds())
                        || featureSelection.metrics().stream()
                                .noneMatch(metric -> metric.evaluationKind().equals("VALIDATION_CONTEXT_GATE")
                                        && metric.selected()
                                        && metric.gateStatus().equals("PASS"))
                        || trainingConfig.featureSelectionMode() == FeatureSelectionMode.REQUIRE_COUNTS
                                && featureSelection.metrics().stream()
                                        .noneMatch(metric ->
                                                metric.evaluationKind().equals("VALIDATION_COUNTS_GATE")
                                                        && metric.selected()
                                                        && metric.gateStatus().equals("PASS")))) {
            throw new IllegalArgumentException("Accepted artifact is not deployable");
        }
        if (acceptanceStatus != ModelAcceptanceStatus.ACCEPTED && acceptanceReasons.isEmpty()) {
            throw new IllegalArgumentException("Rejected artifact needs acceptance reasons");
        }
    }

    public static List<String> expectedThresholdBits() {
        return java.util.stream.IntStream.range(0, OUTPUT_WIDTH)
                .mapToObj(
                        index -> "%016x".formatted(Double.doubleToRawLongBits(ScenarioOrdinalTargets.threshold(index))))
                .toList();
    }

    private static boolean acceptedEvaluationPasses(
            EvaluationSummaryMetadata summary, EvaluationThresholds thresholds) {
        return atMost(summary.groupedMacroMae(), thresholds.maximumGroupedMacroMae())
                && atLeast(summary.groupedMacroSpearman(), thresholds.minimumGroupedMacroSpearman())
                && atLeast(summary.groupedMacroPrecisionAtTen(), thresholds.minimumGroupedMacroPrecisionAtTen())
                && atMost(summary.losoMacroMae(), thresholds.maximumLosoMacroMae())
                && atLeast(summary.losoMacroSpearman(), thresholds.minimumLosoMacroSpearman())
                && atMost(summary.losoWorstScenarioMae(), thresholds.maximumLosoWorstScenarioMae());
    }

    private static boolean atMost(java.util.OptionalDouble value, double threshold) {
        return value.isPresent() && value.getAsDouble() <= threshold;
    }

    private static boolean atLeast(java.util.OptionalDouble value, double threshold) {
        return value.isPresent() && value.getAsDouble() >= threshold;
    }

    public boolean deploymentEligible() {
        return acceptanceStatus == ModelAcceptanceStatus.ACCEPTED
                && !featureSet.ablationOnly()
                && requiredScenarios.equals(trainingScenarios);
    }
}
