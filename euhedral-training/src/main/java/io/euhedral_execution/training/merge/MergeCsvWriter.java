package io.euhedral_execution.training.merge;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.data.CalibrationPlanCsv;
import io.euhedral_execution.training.merge.data.MergeRecords.MergeResult;
import io.euhedral_execution.training.merge.data.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.merge.data.MergeRecords.RunCalibration;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResult;
import io.euhedral_execution.training.merge.enums.CalibrationAcceptance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

public final class MergeCsvWriter {

    public static final String CALIBRATIONS_COLUMNS =
            "schema_version,calibration_acceptance,scenario_id,benchmark_run_id,reference_run_id,anchor_set_id,fixed_anchor_count,shared_anchor_count,delta_log,scale_factor,weighted_median_absolute_residual,status,reason";
    public static final String SCENARIOS_COLUMNS =
            "schema_version,scenario_id,environment_id,source_count,available_physical_core_count,source_ratio_numerator,source_ratio_denominator,policy_id,status,total_run_count,accepted_run_count,weak_run_count,uncalibrated_run_count,successful_repetition_count,planned_repetition_count,throughput_p25,throughput_median,throughput_p75,throughput_iqr,median_within_run_relative_iqr,mean_timeout_rate,mean_failure_rate,mean_non_success_rate,bootstrap_median_ci_low,bootstrap_median_ci_high,quality";
    public static final String RANKING_COLUMNS =
            "schema_version,published_rank,policy_id,eligible,required_scenario_count,observed_required_scenario_count,valid_required_scenario_count,coverage_fraction,worst_quality,quality_p25,geometric_mean_quality,cross_scenario_quality_mad,median_relative_iqr,mean_non_success_rate,mean_timeout_rate,missing_scenarios";
    public static final String COVERAGE_COLUMNS =
            "schema_version,policy_id,eligible,required_scenario_count,observed_required_scenario_count,valid_required_scenario_count,measured_scenarios,missing_scenarios,rejected_scenarios";
    public static final String INCOMPLETE_VECTOR_COLUMNS =
            "schema_version,valid_required_scenario_count,observed_required_scenario_count,policy_id";

    private MergeCsvWriter() {}

    public static void write(Path directory, MergeResult result, CalibrationAcceptance acceptance) throws IOException {
        CalibrationPlanCsv.write(directory, result.calibrationPlan());
        Files.writeString(
                directory.resolve("calibration-report.csv"),
                calibrations(result.calibrations(), acceptance),
                StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("scenario-results.csv"), scenarios(result.scenarioResults()), StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("robust-ranking.csv"), ranking(result.robustSummaries()), StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("coverage-report.csv"), coverage(result.robustSummaries()), StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("robust-leaders.vectors.csv"),
                eligibleVectors(result.robustSummaries()),
                StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("incomplete-policies.vectors.csv"),
                incompleteVectors(result.robustSummaries()),
                StandardCharsets.UTF_8);
    }

    private static String calibrations(List<RunCalibration> rows, CalibrationAcceptance acceptance) {
        StringBuilder out = new StringBuilder(CALIBRATIONS_COLUMNS);
        out.append("\n");
        for (RunCalibration row : rows) {
            out.append(csv(
                    "1",
                    acceptance.name(),
                    row.run().descriptor().scenario().canonical(),
                    row.run().descriptor().benchmarkRunId(),
                    row.referenceRunId(),
                    row.anchorSetId(),
                    Integer.toString(row.fixedAnchorCount()),
                    Integer.toString(row.sharedAnchorCount()),
                    optional(row.deltaLog()),
                    optional(row.scaleFactor()),
                    optional(row.weightedMedianAbsoluteResidual()),
                    row.status().name(),
                    row.reason()));
        }
        return out.toString();
    }

    private static String scenarios(List<ScenarioResult> rows) {
        StringBuilder out = new StringBuilder(SCENARIOS_COLUMNS);
        out.append("\n");
        rows.stream()
                .sorted(Comparator.comparing(ScenarioResult::scenario)
                        .thenComparing(row -> row.policy().id()))
                .forEach(row -> out.append(csv(
                        "1",
                        row.scenario().canonical(),
                        row.scenario().environmentId(),
                        Integer.toString(row.scenario().sourceCount()),
                        Integer.toString(row.scenario().availablePhysicalCoreCount()),
                        Integer.toString(row.scenario().ratio().numerator()),
                        Integer.toString(row.scenario().ratio().denominator()),
                        row.policy().id().canonical(),
                        row.status().name(),
                        Integer.toString(row.totalRunCount()),
                        Integer.toString(row.acceptedRunCount()),
                        Integer.toString(row.weakRunCount()),
                        Integer.toString(row.uncalibratedRunCount()),
                        Integer.toString(row.successfulRepetitionCount()),
                        Integer.toString(row.plannedRepetitionCount()),
                        optional(row.throughputP25()),
                        optional(row.throughputMedian()),
                        optional(row.throughputP75()),
                        optional(row.throughputIqr()),
                        optional(row.medianWithinRunRelativeIqr()),
                        optional(row.meanTimeoutRate()),
                        optional(row.meanFailureRate()),
                        optional(row.meanNonSuccessRate()),
                        optional(row.bootstrapMedianCiLow()),
                        optional(row.bootstrapMedianCiHigh()),
                        optional(row.quality()))));
        return out.toString();
    }

    private static String ranking(List<RobustPolicySummary> rows) {
        StringBuilder out = new StringBuilder(RANKING_COLUMNS);
        out.append("\n");
        List<RobustPolicySummary> sorted =
                rows.stream().sorted(PolicyComparator.PUBLISHED_ORDER).toList();
        int rank = 0;
        for (RobustPolicySummary row : sorted) {
            String published = row.eligible() ? Integer.toString(++rank) : "";
            out.append(csv(
                    "1",
                    published,
                    row.policy().id().canonical(),
                    Boolean.toString(row.eligible()),
                    Integer.toString(row.requiredScenarioCount()),
                    Integer.toString(row.observedRequiredScenarioCount()),
                    Integer.toString(row.validRequiredScenarioCount()),
                    Double.toString(row.coverageFraction()),
                    optional(row.worstQuality()),
                    optional(row.qualityP25()),
                    optional(row.geometricMeanQuality()),
                    optional(row.crossScenarioQualityMad()),
                    optional(row.medianRelativeIqr()),
                    optional(row.meanNonSuccessRate()),
                    optional(row.meanTimeoutRate()),
                    scenarios(row.missingScenarios())));
        }
        return out.toString();
    }

    private static String coverage(List<RobustPolicySummary> rows) {
        StringBuilder out = new StringBuilder(COVERAGE_COLUMNS);
        out.append("\n");
        rows.stream()
                .sorted(Comparator.comparing(row -> row.policy().id()))
                .forEach(row -> out.append(csv(
                        "1",
                        row.policy().id().canonical(),
                        Boolean.toString(row.eligible()),
                        Integer.toString(row.requiredScenarioCount()),
                        Integer.toString(row.observedRequiredScenarioCount()),
                        Integer.toString(row.validRequiredScenarioCount()),
                        scenarios(row.measuredScenarios()),
                        scenarios(row.missingScenarios()),
                        scenarios(row.rejectedScenarios()))));
        return out.toString();
    }

    private static String eligibleVectors(List<RobustPolicySummary> rows) {
        StringBuilder out = new StringBuilder(vectorHeader("robust_rank"));
        int rank = 0;
        for (RobustPolicySummary row : rows.stream()
                .filter(RobustPolicySummary::eligible)
                .sorted(PolicyComparator.BEST_FIRST)
                .toList()) {
            out.append(vectorRow(Integer.toString(++rank), row.policy()));
        }
        return out.toString();
    }

    private static String incompleteVectors(List<RobustPolicySummary> rows) {
        StringBuilder out = new StringBuilder(INCOMPLETE_VECTOR_COLUMNS);
        appendWeightHeaders(out);
        out.append('\n');
        for (RobustPolicySummary row : rows.stream()
                .filter(item -> !item.eligible())
                .sorted(PolicyComparator.PUBLISHED_ORDER)
                .toList()) {
            out.append("1,")
                    .append(row.validRequiredScenarioCount())
                    .append(',')
                    .append(row.observedRequiredScenarioCount())
                    .append(',')
                    .append(row.policy().id().canonical());
            appendWeights(out, row.policy());
            out.append('\n');
        }
        return out.toString();
    }

    private static String vectorHeader(String rankName) {
        StringBuilder out =
                new StringBuilder("schema_version,").append(rankName).append(",policy_id");
        appendWeightHeaders(out);
        return out.append('\n').toString();
    }

    private static String vectorRow(String rank, PolicyVector policy) {
        StringBuilder out = new StringBuilder("1,")
                .append(rank)
                .append(',')
                .append(policy.id().canonical());
        appendWeights(out, policy);
        return out.append('\n').toString();
    }

    private static void appendWeightHeaders(StringBuilder out) {
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            out.append(String.format(",weight_%02d_bits", i));
        }
    }

    private static void appendWeights(StringBuilder out, PolicyVector policy) {
        for (double weight : policy.copyWeights()) {
            out.append(',').append(String.format("%016x", Double.doubleToRawLongBits(weight)));
        }
    }

    private static String optional(OptionalDouble value) {
        return value.isPresent() ? Double.toString(value.getAsDouble()) : "";
    }

    private static String scenarios(Collection<SourceScenario> values) {
        return values.stream()
                .map(SourceScenario::canonical)
                .sorted()
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
    }

    private static String csv(String... fields) {
        return String.join(",", fields) + "\n";
    }
}
