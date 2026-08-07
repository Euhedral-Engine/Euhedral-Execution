package io.euhedral_execution.training.learning.metadata;

import io.euhedral_execution.training.data.PartitionCounts;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.SourceRatio;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.EvaluationThresholds;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.enums.FeatureSelectionMode;
import io.euhedral_execution.training.learning.enums.ModelAcceptanceStatus;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.statistics.AblationMetric;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ScenarioModelMetadataCodec {

    public static final String FILE_NAME = "model-metadata.json";

    public static void write(Path path, ScenarioModelMetadata metadata) throws IOException {
        Files.writeString(path, encode(metadata), StandardCharsets.UTF_8);
    }

    public static ScenarioModelMetadata read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Missing " + FILE_NAME
                    + "; a pooled 28-input artifact is not compatible and must be retrained "
                    + "from Phase 1 scenario records");
        }
        return decode(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static String encode(ScenarioModelMetadata metadata) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.field("artifact_type", ScenarioModelMetadata.ARTIFACT_TYPE);
        writer.field("schema_version", metadata.schemaVersion());
        writer.field("objective_version", metadata.objectiveVersion());
        writer.field("learning_schema_version", ScenarioModelMetadata.LEARNING_SCHEMA_VERSION);
        writer.field("policy_id_scheme", ScenarioModelMetadata.POLICY_ID_SCHEME);
        writer.field("policy_width", ScenarioModelMetadata.POLICY_WIDTH);
        writer.field("feature_schema_id", metadata.featureSet().schemaId());
        writer.field("feature_width", metadata.featureSet().width());
        writer.fieldStrings("feature_names", metadata.normalizer().featureNames());
        writer.fieldStrings("feature_mean_bits", rawBits(metadata.normalizer().means()));
        writer.fieldStrings("feature_scale_bits", rawBits(metadata.normalizer().scales()));
        writer.fieldBooleans("feature_constant", metadata.normalizer().constantFeatures());
        writer.field("output_width", ScenarioModelMetadata.OUTPUT_WIDTH);
        writer.fieldStrings("ordinal_threshold_bits", metadata.ordinalThresholdBits());
        writer.field("architecture", metadata.architecture());
        writer.field("ensemble_members", metadata.members().size());
        writer.field("member_model_name", metadata.memberModelName());
        writer.name("members");
        writer.beginArray();
        for (MemberMetadata member : metadata.members()) {
            writeMember(writer, member);
        }
        writer.endArray();
        writer.field("split_algorithm", metadata.splitAlgorithm());
        writer.field("split_seed_hex", hex(metadata.splitSeed()));
        writer.field("model_seed_hex", hex(metadata.modelSeed()));
        writer.field("dataset_fingerprint_sha256", metadata.datasetFingerprintSha256());
        writer.field("include_weak_calibration_rows", metadata.includeWeakCalibrationRows());
        writer.name("required_scenarios");
        writeScenarios(writer, metadata.requiredScenarios());
        writer.name("training_scenarios");
        writeScenarios(writer, metadata.trainingScenarios());
        writer.name("partition_counts");
        writePartitionCounts(writer, metadata.partitionCounts());
        writer.name("training_config");
        writeTrainingConfig(writer, metadata.trainingConfig());
        writer.name("evaluation_thresholds");
        writeThresholds(writer, metadata.trainingConfig().thresholds());
        writer.name("feature_selection");
        writeFeatureSelection(writer, metadata.featureSelection());
        writer.name("evaluation_summary");
        writeEvaluationSummary(writer, metadata.evaluationSummary());
        writer.field("acceptance_status", metadata.acceptanceStatus().name());
        writer.fieldStrings("acceptance_reasons", metadata.acceptanceReasons());
        writer.name("producer");
        writeProducer(writer, metadata.producer());
        writer.name("metadata_probe");
        writeProbe(writer, metadata.metadataProbe());
        writer.endObject();
        return writer.finish();
    }

    public static ScenarioModelMetadata decode(String json) throws IOException {
        try {
            return decodeStrict(json);
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("Invalid scenario model metadata", error);
        }
    }

    private static ScenarioModelMetadata decodeStrict(String json) throws IOException {
        Object value;
        try {
            value = new JsonParser(json).parse();
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid scenario model metadata JSON", error);
        }
        Map<String, Object> root = object(value, "metadata");
        requireKeys(
                root,
                List.of(
                        "artifact_type",
                        "schema_version",
                        "objective_version",
                        "learning_schema_version",
                        "policy_id_scheme",
                        "policy_width",
                        "feature_schema_id",
                        "feature_width",
                        "feature_names",
                        "feature_mean_bits",
                        "feature_scale_bits",
                        "feature_constant",
                        "output_width",
                        "ordinal_threshold_bits",
                        "architecture",
                        "ensemble_members",
                        "member_model_name",
                        "members",
                        "split_algorithm",
                        "split_seed_hex",
                        "model_seed_hex",
                        "dataset_fingerprint_sha256",
                        "include_weak_calibration_rows",
                        "required_scenarios",
                        "training_scenarios",
                        "partition_counts",
                        "training_config",
                        "evaluation_thresholds",
                        "feature_selection",
                        "evaluation_summary",
                        "acceptance_status",
                        "acceptance_reasons",
                        "producer",
                        "metadata_probe"),
                "metadata");
        requireString(root, "artifact_type", ScenarioModelMetadata.ARTIFACT_TYPE);
        int schema = integer(root, "schema_version");
        if (schema != ScenarioModelMetadata.SCHEMA_VERSION) {
            throw new IOException("Unknown scenario model schema " + schema);
        }
        String objective = string(root, "objective_version");
        if (!objective.equals(ScenarioModelMetadata.OBJECTIVE_VERSION)) {
            throw new IOException("Unknown scenario objective " + objective);
        }
        requireInteger(root, "learning_schema_version", 1);
        requireString(root, "policy_id_scheme", "p1");
        requireInteger(root, "policy_width", 28);
        ScenarioFeatureSet featureSet = featureSet(string(root, "feature_schema_id"));
        requireInteger(root, "feature_width", featureSet.width());
        List<String> featureNames = strings(root, "feature_names");
        double[] means = doublesFromBits(strings(root, "feature_mean_bits"));
        double[] scales = doublesFromBits(strings(root, "feature_scale_bits"));
        boolean[] constants = booleans(root, "feature_constant");
        FeatureNormalizer normalizer =
                new FeatureNormalizer(featureSet.schemaId(), featureNames, means, scales, constants);
        requireInteger(root, "output_width", 9);
        List<String> thresholds = strings(root, "ordinal_threshold_bits");
        requireString(root, "architecture", ScenarioModelMetadata.ARCHITECTURE);
        int ensembleMembers = integer(root, "ensemble_members");
        requireString(root, "member_model_name", ScenarioModelMetadata.MEMBER_MODEL_NAME);
        List<MemberMetadata> members = readMembers(array(root, "members"));
        if (members.size() != ensembleMembers) {
            throw new IOException("Ensemble member count mismatch");
        }
        requireString(root, "split_algorithm", ScenarioModelMetadata.SPLIT_ALGORITHM);
        long splitSeed = parseHex64(string(root, "split_seed_hex"));
        long modelSeed = parseHex64(string(root, "model_seed_hex"));
        String fingerprint = string(root, "dataset_fingerprint_sha256");
        boolean includeWeak = bool(root, "include_weak_calibration_rows");
        SortedSet<SourceScenario> required = readScenarios(array(root, "required_scenarios"), "required_scenarios");
        SortedSet<SourceScenario> training = readScenarios(array(root, "training_scenarios"), "training_scenarios");
        PartitionCounts partitionCounts = readPartitionCounts(object(root.get("partition_counts"), "partition_counts"));
        EvaluationThresholds evaluationThresholds =
                readThresholds(object(root.get("evaluation_thresholds"), "evaluation_thresholds"));
        ScenarioTrainingConfig config =
                readTrainingConfig(object(root.get("training_config"), "training_config"), evaluationThresholds);
        FeatureSelectionDecision selection =
                readFeatureSelection(object(root.get("feature_selection"), "feature_selection"));
        EvaluationSummaryMetadata summary =
                readEvaluationSummary(object(root.get("evaluation_summary"), "evaluation_summary"));
        ModelAcceptanceStatus acceptance = enumValue(ModelAcceptanceStatus.class, string(root, "acceptance_status"));
        List<String> reasons = strings(root, "acceptance_reasons");
        ProducerMetadata producer = readProducer(object(root.get("producer"), "producer"));
        MetadataProbe probe = readProbe(object(root.get("metadata_probe"), "metadata_probe"));
        try {
            return new ScenarioModelMetadata(
                    schema,
                    objective,
                    featureSet,
                    normalizer,
                    thresholds,
                    ScenarioModelMetadata.ARCHITECTURE,
                    ScenarioModelMetadata.MEMBER_MODEL_NAME,
                    members,
                    ScenarioModelMetadata.SPLIT_ALGORITHM,
                    splitSeed,
                    modelSeed,
                    fingerprint,
                    includeWeak,
                    required,
                    training,
                    partitionCounts,
                    config,
                    selection,
                    summary,
                    acceptance,
                    reasons,
                    producer,
                    probe);
        } catch (RuntimeException error) {
            throw new IOException("Invalid scenario model metadata", error);
        }
    }

    private static void writeMember(JsonWriter writer, MemberMetadata member) {
        writer.beginObject();
        writer.field("index", member.index());
        writer.field("seed_hex", hex(member.seed()));
        writer.field("best_epoch", member.bestEpoch());
        writer.field("path", member.relativePath());
        writer.field("sha256", member.sha256());
        writer.endObject();
    }

    private static List<MemberMetadata> readMembers(List<Object> values) throws IOException {
        ArrayList<MemberMetadata> members = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> member = object(value, "member");
            requireKeys(member, List.of("index", "seed_hex", "best_epoch", "path", "sha256"), "member");
            members.add(new MemberMetadata(
                    integer(member, "index"),
                    parseHex64(string(member, "seed_hex")),
                    integer(member, "best_epoch"),
                    string(member, "path"),
                    string(member, "sha256")));
        }
        return List.copyOf(members);
    }

    private static void writeScenarios(JsonWriter writer, Iterable<SourceScenario> scenarios) {
        writer.beginArray();
        for (SourceScenario scenario : scenarios) {
            writer.beginObject();
            writer.field("scenario_id", scenario.canonical());
            writer.field("environment_id", scenario.environmentId());
            writer.field("source_count", scenario.sourceCount());
            writer.field("available_physical_core_count", scenario.availablePhysicalCoreCount());
            writer.field("source_ratio_numerator", scenario.ratio().numerator());
            writer.field("source_ratio_denominator", scenario.ratio().denominator());
            writer.endObject();
        }
        writer.endArray();
    }

    private static SortedSet<SourceScenario> readScenarios(List<Object> values, String name) throws IOException {
        TreeSet<SourceScenario> scenarios = new TreeSet<>();
        SourceScenario previous = null;
        for (Object value : values) {
            Map<String, Object> object = object(value, name);
            requireKeys(
                    object,
                    List.of(
                            "scenario_id",
                            "environment_id",
                            "source_count",
                            "available_physical_core_count",
                            "source_ratio_numerator",
                            "source_ratio_denominator"),
                    name);
            SourceScenario scenario = new SourceScenario(
                    string(object, "environment_id"),
                    integer(object, "source_count"),
                    integer(object, "available_physical_core_count"),
                    new SourceRatio(
                            integer(object, "source_ratio_numerator"), integer(object, "source_ratio_denominator")));
            if (!scenario.canonical().equals(string(object, "scenario_id"))
                    || previous != null && previous.compareTo(scenario) >= 0
                    || !scenarios.add(scenario)) {
                throw new IOException("Invalid or unsorted scenario metadata");
            }
            previous = scenario;
        }
        return Collections.unmodifiableSortedSet(scenarios);
    }

    private static void writePartitionCounts(JsonWriter writer, PartitionCounts counts) {
        writer.beginObject();
        writer.name("policy_counts");
        writeStringIntegerMap(writer, counts.policyCounts());
        writer.name("row_counts");
        writeStringIntegerMap(writer, counts.rowCounts());
        writer.name("scenario_row_counts");
        writer.beginObject();
        for (Map.Entry<String, SortedMap<SourceScenario, Integer>> entry :
                counts.scenarioRowCounts().entrySet()) {
            writer.name(entry.getKey());
            writer.beginObject();
            for (Map.Entry<SourceScenario, Integer> count : entry.getValue().entrySet()) {
                writer.field(count.getKey().canonical(), count.getValue());
            }
            writer.endObject();
        }
        writer.endObject();
        writer.endObject();
    }

    private static PartitionCounts readPartitionCounts(Map<String, Object> value) throws IOException {
        requireKeys(value, List.of("policy_counts", "row_counts", "scenario_row_counts"), "partition_counts");
        SortedMap<String, Integer> policies = readStringIntegerMap(object(value.get("policy_counts"), "policy_counts"));
        SortedMap<String, Integer> rows = readStringIntegerMap(object(value.get("row_counts"), "row_counts"));
        TreeMap<String, SortedMap<SourceScenario, Integer>> scenarios = new TreeMap<>();
        Map<String, Object> outer = object(value.get("scenario_row_counts"), "scenario_row_counts");
        for (Map.Entry<String, Object> entry : outer.entrySet()) {
            TreeMap<SourceScenario, Integer> counts = new TreeMap<>();
            for (Map.Entry<String, Object> count :
                    object(entry.getValue(), entry.getKey()).entrySet()) {
                SourceScenario scenario = parseScenarioCanonical(count.getKey());
                counts.put(scenario, exactInteger(count.getValue(), count.getKey()));
            }
            scenarios.put(entry.getKey(), counts);
        }
        return new PartitionCounts(policies, rows, scenarios);
    }

    private static void writeTrainingConfig(JsonWriter writer, ScenarioTrainingConfig config) {
        writer.beginObject();
        writer.field("split_seed_hex", hex(config.splitSeed()));
        writer.field("model_seed_hex", hex(config.modelSeed()));
        writer.field("device", config.device());
        writer.field("ensemble_members", config.ensembleMembers());
        writer.field("loso_evaluation_members", config.losoEvaluationMembers());
        writer.field("ablation_members", config.ablationMembers());
        writer.field("max_epochs", config.maxEpochs());
        writer.field("patience", config.patience());
        writer.field("effective_batch_size", config.batchSize());
        writer.field("learning_rate_bits", floatBits(config.learningRate()));
        writer.field("weight_decay_bits", floatBits(config.weightDecay()));
        writer.field("label_smoothing_bits", floatBits(config.labelSmoothing()));
        writer.field("minimum_train_policy_groups", config.minimumTrainPolicyGroups());
        writer.field("minimum_validation_policy_groups", config.minimumValidationPolicyGroups());
        writer.field("minimum_test_policy_groups", config.minimumTestPolicyGroups());
        writer.field("minimum_train_rows_per_scenario", config.minimumTrainRowsPerScenario());
        writer.field("minimum_validation_rows_per_scenario", config.minimumValidationRowsPerScenario());
        writer.field("minimum_test_rows_per_scenario", config.minimumTestRowsPerScenario());
        writer.field("include_weak_calibration_rows", config.includeWeakCalibrationRows());
        writer.field("require_target_variation", config.requireTargetVariation());
        writer.field("feature_selection_mode", config.featureSelectionMode().name());
        writer.endObject();
    }

    private static ScenarioTrainingConfig readTrainingConfig(Map<String, Object> value, EvaluationThresholds thresholds)
            throws IOException {
        requireKeys(
                value,
                List.of(
                        "split_seed_hex",
                        "model_seed_hex",
                        "device",
                        "ensemble_members",
                        "loso_evaluation_members",
                        "ablation_members",
                        "max_epochs",
                        "patience",
                        "effective_batch_size",
                        "learning_rate_bits",
                        "weight_decay_bits",
                        "label_smoothing_bits",
                        "minimum_train_policy_groups",
                        "minimum_validation_policy_groups",
                        "minimum_test_policy_groups",
                        "minimum_train_rows_per_scenario",
                        "minimum_validation_rows_per_scenario",
                        "minimum_test_rows_per_scenario",
                        "include_weak_calibration_rows",
                        "require_target_variation",
                        "feature_selection_mode"),
                "training_config");
        try {
            return new ScenarioTrainingConfig(
                    parseHex64(string(value, "split_seed_hex")),
                    parseHex64(string(value, "model_seed_hex")),
                    string(value, "device"),
                    integer(value, "ensemble_members"),
                    integer(value, "loso_evaluation_members"),
                    integer(value, "ablation_members"),
                    integer(value, "max_epochs"),
                    integer(value, "patience"),
                    integer(value, "effective_batch_size"),
                    parseFloatBits(string(value, "learning_rate_bits")),
                    parseFloatBits(string(value, "weight_decay_bits")),
                    parseFloatBits(string(value, "label_smoothing_bits")),
                    integer(value, "minimum_train_policy_groups"),
                    integer(value, "minimum_validation_policy_groups"),
                    integer(value, "minimum_test_policy_groups"),
                    integer(value, "minimum_train_rows_per_scenario"),
                    integer(value, "minimum_validation_rows_per_scenario"),
                    integer(value, "minimum_test_rows_per_scenario"),
                    bool(value, "include_weak_calibration_rows"),
                    bool(value, "require_target_variation"),
                    enumValue(FeatureSelectionMode.class, string(value, "feature_selection_mode")),
                    thresholds);
        } catch (RuntimeException error) {
            throw new IOException("Invalid training configuration metadata", error);
        }
    }

    private static void writeThresholds(JsonWriter writer, EvaluationThresholds thresholds) {
        writer.beginObject();
        writer.field("maximum_grouped_macro_mae", thresholds.maximumGroupedMacroMae());
        writer.field("minimum_grouped_macro_spearman", thresholds.minimumGroupedMacroSpearman());
        writer.field("minimum_grouped_macro_precision_at_ten", thresholds.minimumGroupedMacroPrecisionAtTen());
        writer.field("maximum_loso_macro_mae", thresholds.maximumLosoMacroMae());
        writer.field("minimum_loso_macro_spearman", thresholds.minimumLosoMacroSpearman());
        writer.field("maximum_loso_worst_scenario_mae", thresholds.maximumLosoWorstScenarioMae());
        writer.field("minimum_context_mae_improvement", thresholds.minimumContextMaeImprovement());
        writer.field("minimum_context_spearman_improvement", thresholds.minimumContextSpearmanImprovement());
        writer.field("maximum_context_mae_regression", thresholds.maximumContextMaeRegression());
        writer.field("maximum_context_spearman_regression", thresholds.maximumContextSpearmanRegression());
        writer.field(
                "minimum_counts_cross_environment_mae_improvement",
                thresholds.minimumCountsCrossEnvironmentMaeImprovement());
        writer.field("maximum_counts_spearman_regression", thresholds.maximumCountsSpearmanRegression());
        writer.field(
                "maximum_counts_worst_environment_mae_regression",
                thresholds.maximumCountsWorstEnvironmentMaeRegression());
        writer.endObject();
    }

    private static EvaluationThresholds readThresholds(Map<String, Object> value) throws IOException {
        List<String> keys = List.of(
                "maximum_grouped_macro_mae",
                "minimum_grouped_macro_spearman",
                "minimum_grouped_macro_precision_at_ten",
                "maximum_loso_macro_mae",
                "minimum_loso_macro_spearman",
                "maximum_loso_worst_scenario_mae",
                "minimum_context_mae_improvement",
                "minimum_context_spearman_improvement",
                "maximum_context_mae_regression",
                "maximum_context_spearman_regression",
                "minimum_counts_cross_environment_mae_improvement",
                "maximum_counts_spearman_regression",
                "maximum_counts_worst_environment_mae_regression");
        requireKeys(value, keys, "evaluation_thresholds");
        return new EvaluationThresholds(
                number(value, keys.get(0)),
                number(value, keys.get(1)),
                number(value, keys.get(2)),
                number(value, keys.get(3)),
                number(value, keys.get(4)),
                number(value, keys.get(5)),
                number(value, keys.get(6)),
                number(value, keys.get(7)),
                number(value, keys.get(8)),
                number(value, keys.get(9)),
                number(value, keys.get(10)),
                number(value, keys.get(11)),
                number(value, keys.get(12)));
    }

    private static void writeFeatureSelection(JsonWriter writer, FeatureSelectionDecision selection) {
        writer.beginObject();
        writer.field("requested_mode", selection.requestedMode().name());
        writer.field("selected_feature_set", selection.selectedFeatureSet().schemaId());
        writer.field("reason", selection.reason());
        writer.name("metrics");
        writer.beginArray();
        for (AblationMetric metric : selection.metrics()) {
            writer.beginObject();
            writer.field("evaluation_kind", metric.evaluationKind());
            writer.field("fold_id", metric.foldId());
            writer.field("feature_schema_id", metric.featureSet().schemaId());
            writer.field("comparison_schema_id", metric.comparisonFeatureSet().schemaId());
            writer.field("scenario_or_environment", metric.scenarioOrEnvironment());
            writer.field("row_count", metric.rowCount());
            writer.fieldOptional("mae", metric.mae());
            writer.fieldOptional("spearman", metric.spearman());
            writer.fieldOptional("mae_delta", metric.maeDelta());
            writer.fieldOptional("spearman_delta", metric.spearmanDelta());
            writer.field("selected", metric.selected());
            writer.field("gate_status", metric.gateStatus());
            writer.field("reason", metric.reason());
            writer.endObject();
        }
        writer.endArray();
        writer.endObject();
    }

    private static FeatureSelectionDecision readFeatureSelection(Map<String, Object> value) throws IOException {
        requireKeys(value, List.of("requested_mode", "selected_feature_set", "reason", "metrics"), "feature_selection");
        ArrayList<AblationMetric> metrics = new ArrayList<>();
        for (Object item : array(value, "metrics")) {
            Map<String, Object> metric = object(item, "ablation metric");
            requireKeys(
                    metric,
                    List.of(
                            "evaluation_kind",
                            "fold_id",
                            "feature_schema_id",
                            "comparison_schema_id",
                            "scenario_or_environment",
                            "row_count",
                            "mae",
                            "spearman",
                            "mae_delta",
                            "spearman_delta",
                            "selected",
                            "gate_status",
                            "reason"),
                    "ablation metric");
            metrics.add(new AblationMetric(
                    string(metric, "evaluation_kind"),
                    string(metric, "fold_id"),
                    featureSet(string(metric, "feature_schema_id")),
                    featureSet(string(metric, "comparison_schema_id")),
                    string(metric, "scenario_or_environment"),
                    integer(metric, "row_count"),
                    optional(metric, "mae"),
                    optional(metric, "spearman"),
                    optional(metric, "mae_delta"),
                    optional(metric, "spearman_delta"),
                    bool(metric, "selected"),
                    string(metric, "gate_status"),
                    string(metric, "reason")));
        }
        return new FeatureSelectionDecision(
                enumValue(FeatureSelectionMode.class, string(value, "requested_mode")),
                featureSet(string(value, "selected_feature_set")),
                metrics,
                string(value, "reason"));
    }

    private static void writeEvaluationSummary(JsonWriter writer, EvaluationSummaryMetadata summary) {
        writer.beginObject();
        writer.field("grouped_report", summary.groupedReport());
        writer.field("loso_report", summary.losoReport());
        writer.fieldOptional("grouped_macro_mae", summary.groupedMacroMae());
        writer.fieldOptional("grouped_macro_spearman", summary.groupedMacroSpearman());
        writer.fieldOptional("grouped_macro_precision_at_ten", summary.groupedMacroPrecisionAtTen());
        writer.fieldOptional("loso_macro_mae", summary.losoMacroMae());
        writer.fieldOptional("loso_macro_spearman", summary.losoMacroSpearman());
        writer.fieldOptional("loso_worst_scenario_mae", summary.losoWorstScenarioMae());
        writer.endObject();
    }

    private static EvaluationSummaryMetadata readEvaluationSummary(Map<String, Object> value) throws IOException {
        requireKeys(
                value,
                List.of(
                        "grouped_report",
                        "loso_report",
                        "grouped_macro_mae",
                        "grouped_macro_spearman",
                        "grouped_macro_precision_at_ten",
                        "loso_macro_mae",
                        "loso_macro_spearman",
                        "loso_worst_scenario_mae"),
                "evaluation_summary");
        return new EvaluationSummaryMetadata(
                string(value, "grouped_report"),
                string(value, "loso_report"),
                optional(value, "grouped_macro_mae"),
                optional(value, "grouped_macro_spearman"),
                optional(value, "grouped_macro_precision_at_ten"),
                optional(value, "loso_macro_mae"),
                optional(value, "loso_macro_spearman"),
                optional(value, "loso_worst_scenario_mae"));
    }

    private static void writeProducer(JsonWriter writer, ProducerMetadata producer) {
        writer.beginObject();
        writer.field("commit_sha", producer.commitSha());
        writer.field("dirty_working_tree", producer.dirtyWorkingTree());
        writer.field("runtime", producer.runtime());
        writer.field("runtime_version", producer.runtimeVersion());
        writer.field("training_device", producer.trainingDevice());
        writer.endObject();
    }

    private static ProducerMetadata readProducer(Map<String, Object> value) throws IOException {
        requireKeys(
                value,
                List.of("commit_sha", "dirty_working_tree", "runtime", "runtime_version", "training_device"),
                "producer");
        return new ProducerMetadata(
                string(value, "commit_sha"),
                bool(value, "dirty_working_tree"),
                string(value, "runtime"),
                string(value, "runtime_version"),
                string(value, "training_device"));
    }

    private static void writeProbe(JsonWriter writer, MetadataProbe probe) {
        writer.beginObject();
        writer.field("policy_id", probe.policyId().canonical());
        writer.field("scenario_id", probe.scenario().canonical());
        writer.fieldStrings("prediction_raw_bits", probe.predictionRawBits());
        writer.field("producing_device", probe.producingDevice());
        writer.endObject();
    }

    private static MetadataProbe readProbe(Map<String, Object> value) throws IOException {
        requireKeys(
                value,
                List.of("policy_id", "scenario_id", "prediction_raw_bits", "producing_device"),
                "metadata_probe");
        return new MetadataProbe(
                PolicyId.parse(string(value, "policy_id")),
                parseScenarioCanonical(string(value, "scenario_id")),
                strings(value, "prediction_raw_bits"),
                string(value, "producing_device"));
    }

    private static SourceScenario parseScenarioCanonical(String canonical) throws IOException {
        try {
            return SourceScenario.parse(canonical);
        } catch (IllegalArgumentException error) {
            throw new IOException("Malformed scenario ID " + canonical, error);
        }
    }

    private static SortedMap<String, Integer> readStringIntegerMap(Map<String, Object> source) throws IOException {
        TreeMap<String, Integer> result = new TreeMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), exactInteger(entry.getValue(), entry.getKey()));
        }
        return result;
    }

    private static void writeStringIntegerMap(JsonWriter writer, Map<String, Integer> map) {
        writer.beginObject();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            writer.field(entry.getKey(), entry.getValue());
        }
        writer.endObject();
    }

    private static List<String> rawBits(double[] values) {
        return Arrays.stream(values)
                .mapToObj(value -> "%016x".formatted(Double.doubleToRawLongBits(value)))
                .toList();
    }

    private static double[] doublesFromBits(List<String> values) throws IOException {
        double[] result = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            String bits = values.get(i);
            if (!bits.matches("[0-9a-f]{16}")) {
                throw new IOException("Malformed double bits");
            }
            result[i] = Double.longBitsToDouble(Long.parseUnsignedLong(bits, 16));
        }
        return result;
    }

    private static String floatBits(float value) {
        return "%08x".formatted(Float.floatToRawIntBits(value));
    }

    private static float parseFloatBits(String value) throws IOException {
        if (!value.matches("[0-9a-f]{8}")) {
            throw new IOException("Malformed float bits");
        }
        return Float.intBitsToFloat(Integer.parseUnsignedInt(value, 16));
    }

    private static String hex(long value) {
        return "%016x".formatted(value);
    }

    private static long parseHex64(String value) throws IOException {
        if (!value.matches("[0-9a-f]{16}")) {
            throw new IOException("Malformed 64-bit hex");
        }
        return Long.parseUnsignedLong(value, 16);
    }

    private static ScenarioFeatureSet featureSet(String schemaId) throws IOException {
        return Arrays.stream(ScenarioFeatureSet.values())
                .filter(value -> value.schemaId().equals(schemaId))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown feature schema " + schemaId));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) throws IOException {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw new IOException("Unknown " + type.getSimpleName() + " " + value, error);
        }
    }

    private static void requireKeys(Map<String, Object> object, List<String> keys, String name) throws IOException {
        if (object.size() != keys.size() || !object.keySet().containsAll(keys)) {
            throw new IOException("Unexpected or missing fields in " + name);
        }
    }

    private static Map<String, Object> object(Object value, String name) throws IOException {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IOException(name + " must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) raw;
        return result;
    }

    private static List<Object> array(Map<String, Object> object, String key) throws IOException {
        return array(object.get(key), key);
    }

    private static List<Object> array(Object value, String name) throws IOException {
        if (!(value instanceof List<?> raw)) {
            throw new IOException(name + " must be an array");
        }
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) raw;
        return result;
    }

    private static String string(Map<String, Object> object, String key) throws IOException {
        Object value = object.get(key);
        if (!(value instanceof String text)) {
            throw new IOException(key + " must be a string");
        }
        return text;
    }

    private static List<String> strings(Map<String, Object> object, String key) throws IOException {
        ArrayList<String> result = new ArrayList<>();
        for (Object value : array(object, key)) {
            if (!(value instanceof String text)) {
                throw new IOException(key + " must contain strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static boolean[] booleans(Map<String, Object> object, String key) throws IOException {
        List<Object> values = array(object, key);
        boolean[] result = new boolean[values.size()];
        for (int i = 0; i < values.size(); i++) {
            if (!(values.get(i) instanceof Boolean value)) {
                throw new IOException(key + " must contain booleans");
            }
            result[i] = value;
        }
        return result;
    }

    private static int integer(Map<String, Object> object, String key) throws IOException {
        return exactInteger(object.get(key), key);
    }

    private static int exactInteger(Object value, String name) throws IOException {
        if (!(value instanceof Long number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IOException(name + " must be an integer");
        }
        return number.intValue();
    }

    private static double number(Map<String, Object> object, String key) throws IOException {
        Object value = object.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IOException(key + " must be finite");
        }
        return number.doubleValue();
    }

    private static boolean bool(Map<String, Object> object, String key) throws IOException {
        Object value = object.get(key);
        if (!(value instanceof Boolean result)) {
            throw new IOException(key + " must be boolean");
        }
        return result;
    }

    private static OptionalDouble optional(Map<String, Object> object, String key) throws IOException {
        Object value = object.get(key);
        if (value == null) {
            return OptionalDouble.empty();
        }
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IOException(key + " must be finite or null");
        }
        return OptionalDouble.of(number.doubleValue());
    }

    private static void requireString(Map<String, Object> object, String key, String expected) throws IOException {
        if (!string(object, key).equals(expected)) {
            throw new IOException("Unexpected " + key);
        }
    }

    private static void requireInteger(Map<String, Object> object, String key, int expected) throws IOException {
        if (integer(object, key) != expected) {
            throw new IOException("Unexpected " + key);
        }
    }

    private ScenarioModelMetadataCodec() {}

    private static final class JsonWriter {

        private final StringBuilder output = new StringBuilder();
        private final ArrayList<Context> contexts = new ArrayList<>();
        private int indent;
        private boolean afterName;

        void beginObject() {
            beforeValue();
            output.append('{');
            contexts.add(new Context(true));
            indent++;
        }

        void endObject() {
            Context context = pop(true);
            indent--;
            if (!context.first) {
                newline();
            }
            output.append('}');
        }

        void beginArray() {
            beforeValue();
            output.append('[');
            contexts.add(new Context(false));
            indent++;
        }

        void endArray() {
            Context context = pop(false);
            indent--;
            if (!context.first) {
                newline();
            }
            output.append(']');
        }

        void name(String name) {
            Context context = current(true);
            separate(context);
            quote(name);
            output.append(": ");
            afterName = true;
        }

        void field(String name, String value) {
            name(name);
            quote(value);
            afterName = false;
        }

        void field(String name, int value) {
            name(name);
            output.append(value);
            afterName = false;
        }

        void field(String name, double value) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite JSON number");
            }
            name(name);
            output.append(Double.toString(value));
            afterName = false;
        }

        void field(String name, boolean value) {
            name(name);
            output.append(value);
            afterName = false;
        }

        void fieldOptional(String name, OptionalDouble value) {
            name(name);
            if (value.isPresent()) {
                double number = value.getAsDouble();
                if (!Double.isFinite(number)) {
                    throw new IllegalArgumentException("Non-finite JSON number");
                }
                output.append(Double.toString(number));
            } else {
                output.append("null");
            }
            afterName = false;
        }

        void fieldStrings(String name, List<String> values) {
            name(name);
            beginArray();
            for (String value : values) {
                beforeValue();
                quote(value);
            }
            endArray();
        }

        void fieldBooleans(String name, boolean[] values) {
            name(name);
            beginArray();
            for (boolean value : values) {
                beforeValue();
                output.append(value);
            }
            endArray();
        }

        String finish() {
            if (!contexts.isEmpty() || afterName) {
                throw new IllegalStateException();
            }
            return output.append('\n').toString();
        }

        private void beforeValue() {
            if (afterName) {
                afterName = false;
                return;
            }
            if (!contexts.isEmpty()) {
                Context context = current(false);
                if (context.object) {
                    throw new IllegalStateException("Object value needs name");
                }
                separate(context);
            }
        }

        private void separate(Context context) {
            if (!context.first) {
                output.append(',');
            }
            newline();
            context.first = false;
        }

        private void newline() {
            output.append('\n').append("  ".repeat(indent));
        }

        private Context current(boolean object) {
            if (contexts.isEmpty() || contexts.getLast().object != object) {
                throw new IllegalStateException();
            }
            return contexts.getLast();
        }

        private Context pop(boolean object) {
            Context context = current(object);
            contexts.removeLast();
            return context;
        }

        private void quote(String value) {
            output.append('"');
            for (int offset = 0; offset < value.length(); ) {
                int codePoint = value.codePointAt(offset);
                offset += Character.charCount(codePoint);
                switch (codePoint) {
                    case '"' -> output.append("\\\"");
                    case '\\' -> output.append("\\\\");
                    case '\b' -> output.append("\\b");
                    case '\f' -> output.append("\\f");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> {
                        if (codePoint < 0x20) {
                            output.append("\\u%04x".formatted(codePoint));
                        } else {
                            output.appendCodePoint(codePoint);
                        }
                    }
                }
            }
            output.append('"');
        }

        private static final class Context {

            private final boolean object;
            private boolean first = true;

            private Context(boolean object) {
                this.object = object;
            }
        }
    }

    private static final class JsonParser {

        private final String input;
        private int index;

        private JsonParser(String input) {
            this.input = input;
        }

        Object parse() {
            Object value = value();
            whitespace();
            if (index != input.length()) {
                throw error("Trailing JSON metadata");
            }
            return value;
        }

        private Object value() {
            whitespace();
            if (index >= input.length()) {
                throw error("Unexpected end");
            }
            return switch (input.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            index++;
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            whitespace();
            if (consume('}')) {
                return values;
            }
            while (true) {
                whitespace();
                if (index >= input.length() || input.charAt(index) != '"') {
                    throw error("Expected object key");
                }
                String key = string();
                whitespace();
                expect(':');
                if (values.containsKey(key)) {
                    throw error("Duplicate object key " + key);
                }
                values.put(key, value());
                whitespace();
                if (consume('}')) {
                    return values;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            index++;
            ArrayList<Object> values = new ArrayList<>();
            whitespace();
            if (consume(']')) {
                return values;
            }
            while (true) {
                values.add(value());
                whitespace();
                if (consume(']')) {
                    return values;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < input.length()) {
                char value = input.charAt(index++);
                if (value == '"') {
                    return result.toString();
                }
                if (value < 0x20) {
                    throw error("Control character in string");
                }
                if (value != '\\') {
                    result.append(value);
                    continue;
                }
                if (index >= input.length()) {
                    throw error("Incomplete escape");
                }
                char escape = input.charAt(index++);
                switch (escape) {
                    case '"', '\\', '/' -> result.append(escape);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (index + 4 > input.length()) {
                            throw error("Incomplete Unicode escape");
                        }
                        String hex = input.substring(index, index + 4);
                        if (!hex.matches("[0-9a-fA-F]{4}")) {
                            throw error("Malformed Unicode escape");
                        }
                        result.append((char) Integer.parseInt(hex, 16));
                        index += 4;
                    }
                    default -> throw error("Malformed escape");
                }
            }
            throw error("Unterminated string");
        }

        private Object number() {
            int start = index;
            if (consume('-')) {
                if (index >= input.length()) {
                    throw error("Incomplete number");
                }
            }
            if (consume('0')) {
                if (index < input.length() && Character.isDigit(input.charAt(index))) {
                    throw error("Leading zero");
                }
            } else {
                digits();
            }
            boolean floating = false;
            if (consume('.')) {
                floating = true;
                digits();
            }
            if (index < input.length() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                floating = true;
                index++;
                if (index < input.length() && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                    index++;
                }
                digits();
            }
            String token = input.substring(start, index);
            try {
                if (!floating) {
                    return Long.parseLong(token);
                }
                double value = Double.parseDouble(token);
                if (!Double.isFinite(value)) {
                    throw error("Non-finite number");
                }
                return value;
            } catch (NumberFormatException error) {
                throw error("Malformed number");
            }
        }

        private void digits() {
            int start = index;
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw error("Expected digits");
            }
        }

        private Object literal(String text, Object value) {
            if (!input.startsWith(text, index)) {
                throw error("Malformed literal");
            }
            index += text.length();
            return value;
        }

        private void whitespace() {
            while (index < input.length()
                    && switch (input.charAt(index)) {
                        case ' ', '\t', '\r', '\n' -> true;
                        default -> false;
                    }) {
                index++;
            }
        }

        private boolean consume(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("Expected " + expected);
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + index);
        }
    }
}
