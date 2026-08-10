package io.euhedral_execution.training.learning.output;

import io.euhedral_execution.training.learning.statistics.AblationMetric;
import io.euhedral_execution.training.learning.statistics.LosoEvaluationMetrics;
import io.euhedral_execution.training.learning.statistics.ScenarioEvaluationMetrics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

public final class ScenarioLearningReportWriter {

    public static final String GROUPED_HEADER = "schema_version,evaluation_kind,feature_schema_id,"
            + "fold_id,scenario_id,row_count,policy_count,mae,rmse,mean_bias,spearman,"
            + "actual_top_decile_count,selected_count,precision_at_10,recall_at_10,"
            + "mean_interval_width,interval_coverage_95,mean_epistemic_stddev,"
            + "mean_disagreement_range,status\n";
    public static final String LOSO_HEADER = "schema_version,evaluation_kind,feature_schema_id,fold_id,"
            + "scenario_id,held_out_ratio,ratio_seen_in_fit,fitting_scenario_count,"
            + "fitting_distinct_ratio_count,row_count,policy_count,mae,rmse,mean_bias,spearman,"
            + "actual_top_decile_count,selected_count,precision_at_10,recall_at_10,"
            + "mean_interval_width,interval_coverage_95,mean_epistemic_stddev,"
            + "mean_disagreement_range,status\n";
    public static final String ABLATION_HEADER = "schema_version,evaluation_kind,fold_id,"
            + "feature_schema_id,comparison_schema_id,scenario_or_environment,row_count,mae,"
            + "spearman,mae_delta,spearman_delta,selected,gate_status,reason\n";
    public static final String HISTORY_HEADER = "schema_version,training_kind,fold_id,"
            + "feature_schema_id,member_index,member_seed_hex,epoch,validation_macro_mae,"
            + "validation_macro_spearman,validation_weighted_bce,selected_epoch\n";

    private ScenarioLearningReportWriter() {}

    public static void writeGrouped(Path path, EvaluationSummary summary) throws IOException {
        StringBuilder output = new StringBuilder(GROUPED_HEADER);
        summary.scenarios().stream()
                .sorted(Comparator.comparing(ScenarioEvaluationMetrics::scenario))
                .forEach(metric -> output.append(groupedRow(metric)));
        write(path, output);
    }

    public static void writeLoso(Path path, List<LosoEvaluationMetrics> rows) throws IOException {
        StringBuilder output = new StringBuilder(LOSO_HEADER);
        rows.stream()
                .sorted(Comparator.comparing(row -> row.metrics().scenario()))
                .forEach(row -> output.append(losoRow(row)));
        write(path, output);
    }

    public static void writeAblation(Path path, List<AblationMetric> rows) throws IOException {
        StringBuilder output = new StringBuilder(ABLATION_HEADER);
        rows.stream()
                .sorted(Comparator.comparing(AblationMetric::evaluationKind)
                        .thenComparing(AblationMetric::foldId)
                        .thenComparing(metric -> metric.featureSet().schemaId()))
                .forEach(metric -> output.append(csv(
                        "1",
                        metric.evaluationKind(),
                        metric.foldId(),
                        metric.featureSet().schemaId(),
                        metric.comparisonFeatureSet().schemaId(),
                        metric.scenarioOrEnvironment(),
                        Integer.toString(metric.rowCount()),
                        optional(metric.mae()),
                        optional(metric.spearman()),
                        optional(metric.maeDelta()),
                        optional(metric.spearmanDelta()),
                        Boolean.toString(metric.selected()),
                        metric.gateStatus(),
                        metric.reason())));
        write(path, output);
    }

    public static void writeHistory(Path path, List<TrainingHistoryEntry> rows) throws IOException {
        StringBuilder output = new StringBuilder(HISTORY_HEADER);
        rows.stream()
                .sorted()
                .forEach(row -> output.append(csv(
                        "1",
                        row.trainingKind(),
                        row.foldId(),
                        row.featureSet().schemaId(),
                        Integer.toString(row.memberIndex()),
                        "%016x".formatted(row.memberSeed()),
                        Integer.toString(row.epoch()),
                        Double.toString(row.validationMacroMae()),
                        optional(row.validationMacroSpearman()),
                        Double.toString(row.validationWeightedBce()),
                        Boolean.toString(row.selectedEpoch()))));
        write(path, output);
    }

    private static String groupedRow(ScenarioEvaluationMetrics metric) {
        return csv(
                "1",
                metric.evaluationKind(),
                metric.featureSet().schemaId(),
                metric.foldId(),
                metric.scenario().canonical(),
                Integer.toString(metric.rowCount()),
                Integer.toString(metric.policyCount()),
                Double.toString(metric.mae()),
                Double.toString(metric.rmse()),
                Double.toString(metric.meanBias()),
                optional(metric.spearman()),
                Integer.toString(metric.actualTopDecileCount()),
                Integer.toString(metric.selectedCount()),
                optional(metric.precisionAtTen()),
                optional(metric.recallAtTen()),
                Double.toString(metric.meanIntervalWidth()),
                Double.toString(metric.intervalCoverage95()),
                Double.toString(metric.meanEpistemicStdDev()),
                Double.toString(metric.meanDisagreementRange()),
                metric.status().name());
    }

    private static String losoRow(LosoEvaluationMetrics row) {
        ScenarioEvaluationMetrics metric = row.metrics();
        return csv(
                "1",
                metric.evaluationKind(),
                metric.featureSet().schemaId(),
                metric.foldId(),
                metric.scenario().canonical(),
                Double.toString(row.heldOutRatio()),
                Boolean.toString(row.ratioSeenInFit()),
                Integer.toString(row.fittingScenarioCount()),
                Integer.toString(row.fittingDistinctRatioCount()),
                Integer.toString(metric.rowCount()),
                Integer.toString(metric.policyCount()),
                Double.toString(metric.mae()),
                Double.toString(metric.rmse()),
                Double.toString(metric.meanBias()),
                optional(metric.spearman()),
                Integer.toString(metric.actualTopDecileCount()),
                Integer.toString(metric.selectedCount()),
                optional(metric.precisionAtTen()),
                optional(metric.recallAtTen()),
                Double.toString(metric.meanIntervalWidth()),
                Double.toString(metric.intervalCoverage95()),
                Double.toString(metric.meanEpistemicStdDev()),
                Double.toString(metric.meanDisagreementRange()),
                metric.status().name());
    }

    private static String optional(OptionalDouble value) {
        return value.isPresent() ? Double.toString(value.getAsDouble()) : "";
    }

    private static String csv(String... fields) {
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) {
                row.append(',');
            }
            String field = fields[index];
            if (field.indexOf(',') >= 0
                    || field.indexOf('"') >= 0
                    || field.indexOf('\n') >= 0
                    || field.indexOf('\r') >= 0) {
                row.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                row.append(field);
            }
        }
        return row.append('\n').toString();
    }

    private static void write(Path path, StringBuilder output) throws IOException {
        Files.writeString(path, output.toString(), StandardCharsets.UTF_8);
    }
}
