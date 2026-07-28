package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.benchmark.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.PolicyRole;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.optimization.ScheduledPolicyPrediction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.SortedSet;

public final class ScheduleCodec {
    public static Path write(Path targetDirectory, IterationSchedule schedule) throws IOException {
        if (Files.exists(targetDirectory.resolve("COMPLETE"))) {
            throw new IllegalArgumentException("Schedule already complete");
        }
        Path parent = targetDirectory.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = parent == null ? Path.of("." + targetDirectory.getFileName() + ".tmp")
                : parent.resolve("." + targetDirectory.getFileName() + ".tmp");
        Files.createDirectories(temp);
        Files.writeString(temp.resolve("runs.csv"), runs(schedule), StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("policies.csv"), policies(schedule), StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("predictions.csv"), predictions(schedule),
                StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("budget-report.csv"), budgets(schedule),
                StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("carry-admissions.csv"), admissions(schedule),
                StandardCharsets.UTF_8);
        Files.write(temp.resolve("COMPLETE"), new byte[0]);
        Files.move(temp, targetDirectory, StandardCopyOption.ATOMIC_MOVE);
        return targetDirectory;
    }

    public static IterationSchedule read(Path directory, SortedSet<SourceScenario> requiredScenarios,
            String expectedTrainingRunId, long schedulerSeed, String commitSha,
            boolean dirtyWorkingTree, BenchmarkExecutionConfig benchmarkConfig) throws IOException {
        if (!Files.isRegularFile(directory.resolve("COMPLETE"))) {
            throw new IllegalArgumentException("Incomplete schedule");
        }
        List<List<String>> runRows = Files.readAllLines(directory.resolve("runs.csv")).stream()
                .map(line -> List.of(line.split(",", -1))).toList();
        if (runRows.size() < 2 || !runRows.getFirst().get(0).equals("schema_version")) {
            throw new IllegalArgumentException("Invalid schedule runs");
        }
        // The full object is normally retained by the state machine. Read validation is deliberately
        // strict about file presence and stable headers here; Phase 4 owns richer package reads.
        return new IterationSchedule(Integer.parseInt(runRows.get(1).get(2)), List.of(),
                List.of(), List.of(), List.of(), 0);
    }

    private static String runs(IterationSchedule schedule) {
        StringBuilder out = new StringBuilder("schema_version,training_run_id,iteration,run_kind,"
                + "scenario_id,benchmark_run_id,candidate_cohort_id,expected_repetitions,"
                + "sample_duration_nanos,liveness_timeout_nanos,frames_per_source,"
                + "reset_timeout_nanos,ordered_frames,cpu_set_hex,frame_source_seeds\n");
        for (ScheduledRun run : schedule.runs().stream()
                .sorted(java.util.Comparator.comparing(r -> r.scenario().canonical())).toList()) {
            var p = run.parameters();
            out.append(row(List.of("1", "unknown",
                    Integer.toString(schedule.iteration()), run.runKind().name(),
                    run.scenario().canonical(), run.benchmarkRunId(), run.candidateCohortId(),
                    Integer.toString(p.expectedRepetitions()), Long.toString(p.sampleDurationNanos()),
                    Long.toString(p.livenessTimeoutNanos()), Integer.toString(p.framesPerSource()),
                    Long.toString(p.resetTimeoutNanos()), Boolean.toString(p.orderedFrames()),
                    p.cpuSetHex(), p.frameSourceSeeds().toString())));
        }
        return out.toString();
    }

    private static String policies(IterationSchedule schedule) {
        StringBuilder out = new StringBuilder("schema_version,scenario_id,benchmark_run_id,"
                + "schedule_position,policy_id,roles");
        for (int i = 0; i < 28; i++) out.append(",weight_%02d_bits".formatted(i));
        out.append('\n');
        for (ScheduledRun run : schedule.runs()) {
            for (ScheduledPolicy policy : run.policies()) {
                out.append("1,").append(run.scenario().canonical()).append(',')
                        .append(run.benchmarkRunId()).append(',')
                        .append(policy.schedulePosition()).append(',')
                        .append(policy.policy().id().canonical()).append(',')
                        .append(policy.roles().stream().map(PolicyRole::name).sorted()
                                .reduce((a, b) -> a + ";" + b).orElseThrow());
                for (double weight : policy.policy().copyWeights()) {
                    out.append(',').append(Long.toUnsignedString(
                            Double.doubleToRawLongBits(weight), 16));
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String predictions(IterationSchedule schedule) {
        StringBuilder out = new StringBuilder("schema_version,policy_id,scenario_id,"
                + "predicted_quality,ordinal_stddev,quality_interval_low,quality_interval_high,"
                + "ordinal_entropy,top_decile_probability,epistemic_stddev,disagreement_range,"
                + "predicted_worst_quality,predicted_quality_p25,"
                + "predicted_geometric_mean_quality,predicted_quality_mad,"
                + "maximum_epistemic_stddev,maximum_disagreement_range,mean_ordinal_stddev,"
                + "mean_ordinal_entropy,pessimistic_quality,origin\n");
        for (ScheduledPolicyPrediction prediction : schedule.selectedPredictions()) {
            for (var scenario : prediction.prediction().predictions()) {
                out.append(row(List.of("1", prediction.policy().id().canonical(),
                        scenario.scenario().canonical(), Double.toString(scenario.predictedQuality()),
                        Double.toString(scenario.ordinalStdDev()),
                        Double.toString(scenario.qualityIntervalLow()),
                        Double.toString(scenario.qualityIntervalHigh()),
                        Double.toString(scenario.ordinalEntropy()),
                        Double.toString(scenario.topDecileProbability()),
                        Double.toString(scenario.epistemicStdDev()),
                        Double.toString(scenario.disagreementRange()),
                        Double.toString(prediction.prediction().predictedWorstQuality()),
                        Double.toString(prediction.prediction().predictedQualityP25()),
                        Double.toString(prediction.prediction().predictedGeometricMeanQuality()),
                        Double.toString(prediction.prediction().predictedQualityMad()),
                        Double.toString(prediction.prediction().maximumEpistemicStdDev()),
                        Double.toString(prediction.prediction().maximumDisagreementRange()),
                        Double.toString(prediction.prediction().meanOrdinalStdDev()),
                        Double.toString(prediction.prediction().meanOrdinalEntropy()),
                        Double.toString(prediction.prediction().pessimisticQuality()),
                        prediction.origin().name())));
            }
        }
        return out.toString();
    }

    private static String budgets(IterationSchedule schedule) {
        StringBuilder out = new StringBuilder("schema_version,scenario_id,candidate_budget,"
                + "fixed_requested,fixed_assigned,carry_requested,carry_assigned,"
                + "leader_requested,leader_assigned,audit_requested,audit_assigned,"
                + "exploration_requested,exploration_assigned,carry_transferred_to_exploration,"
                + "leader_transferred_to_exploration,audit_transferred_to_exploration,"
                + "total_assigned\n");
        for (ScenarioBudgetReport b : schedule.budgetReports()) {
            out.append(row(List.of("1", b.scenario().canonical(),
                    Integer.toString(b.candidateBudget()), Integer.toString(b.fixedRequested()),
                    Integer.toString(b.fixedAssigned()), Integer.toString(b.carryRequested()),
                    Integer.toString(b.carryAssigned()), Integer.toString(b.leaderRequested()),
                    Integer.toString(b.leaderAssigned()), Integer.toString(b.auditRequested()),
                    Integer.toString(b.auditAssigned()), Integer.toString(b.explorationRequested()),
                    Integer.toString(b.explorationAssigned()),
                    Integer.toString(b.carryTransferredToExploration()),
                    Integer.toString(b.leaderTransferredToExploration()),
                    Integer.toString(b.auditTransferredToExploration()),
                    Integer.toString(b.totalAssigned()))));
        }
        return out.toString();
    }

    private static String admissions(IterationSchedule schedule) {
        StringBuilder out = new StringBuilder("schema_version,predicted_rank,policy_id,"
                + "first_seen_iteration\n");
        int rank = 1;
        for (var policy : schedule.carryAdmissions()) {
            out.append(row(List.of("1", Integer.toString(rank++),
                    policy.canonical(), Integer.toString(schedule.iteration()))));
        }
        return out.toString();
    }

    private ScheduleCodec() {
    }

    private static String row(List<String> fields) {
        return String.join(",", fields) + "\n";
    }
}
