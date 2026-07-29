package io.euhedral_execution.training.scheduling.io;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.BenchmarkParameters;
import io.euhedral_execution.training.data.FrameSourceSeed;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.PolicyRole;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.learning.data.PolicyPredictionCurve;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.optimization.PredictedPolicyComparator;
import io.euhedral_execution.training.optimization.PredictedPolicyRanker;
import io.euhedral_execution.training.optimization.SchedulerSeeds;
import io.euhedral_execution.training.optimization.data.PredictedPolicySummary;
import io.euhedral_execution.training.optimization.data.ScheduledPolicyPrediction;
import io.euhedral_execution.training.optimization.enums.SchedulePolicyOrigin;
import io.euhedral_execution.training.scheduling.data.IterationSchedule;
import io.euhedral_execution.training.scheduling.data.ScenarioBudgetReport;
import io.euhedral_execution.training.scheduling.data.ScheduledRun;
import io.euhedral_execution.training.scheduling.enums.RunKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

public final class ScheduleCodec {
    private static final List<String> FILES = List.of("runs.csv", "policies.csv",
            "predictions.csv", "budget-report.csv", "carry-admissions.csv", "COMPLETE");
    private static final List<String> RUN_HEADER = List.of("schema_version", "training_run_id",
            "iteration", "run_kind", "scenario_id", "benchmark_run_id",
            "candidate_cohort_id", "expected_repetitions", "sample_duration_nanos",
            "liveness_timeout_nanos", "frames_per_source", "reset_timeout_nanos",
            "ordered_frames", "cpu_set_hex", "frame_source_seeds");
    private static final List<String> PREDICTION_HEADER = List.of("schema_version", "policy_id",
            "scenario_id", "predicted_quality", "ordinal_stddev", "quality_interval_low",
            "quality_interval_high", "ordinal_entropy", "top_decile_probability",
            "epistemic_stddev", "disagreement_range", "predicted_worst_quality",
            "predicted_quality_p25", "predicted_geometric_mean_quality",
            "predicted_quality_mad", "maximum_epistemic_stddev",
            "maximum_disagreement_range", "mean_ordinal_stddev", "mean_ordinal_entropy",
            "pessimistic_quality", "origin");
    private static final List<String> BUDGET_HEADER = List.of("schema_version", "scenario_id",
            "candidate_budget", "fixed_requested", "fixed_assigned", "carry_requested",
            "carry_assigned", "leader_requested", "leader_assigned", "audit_requested",
            "audit_assigned", "exploration_requested", "exploration_assigned",
            "carry_transferred_to_exploration", "leader_transferred_to_exploration",
            "audit_transferred_to_exploration", "total_assigned");
    private static final List<String> ADMISSION_HEADER = List.of("schema_version",
            "predicted_rank", "policy_id", "first_seen_iteration");

    public static Path write(Path targetDirectory, IterationSchedule schedule) throws IOException {
        Path target = targetDirectory.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Schedule target already exists");
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Schedule target requires a parent");
        }
        Files.createDirectories(parent);
        Path temp = parent.resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createDirectory(temp);
        try {
            Files.writeString(temp.resolve("runs.csv"), runs(schedule), StandardCharsets.UTF_8);
            Files.writeString(temp.resolve("policies.csv"), policies(schedule),
                    StandardCharsets.UTF_8);
            Files.writeString(temp.resolve("predictions.csv"), predictions(schedule),
                    StandardCharsets.UTF_8);
            Files.writeString(temp.resolve("budget-report.csv"), budgets(schedule),
                    StandardCharsets.UTF_8);
            Files.writeString(temp.resolve("carry-admissions.csv"), admissions(schedule),
                    StandardCharsets.UTF_8);
            Files.write(temp.resolve("COMPLETE"), new byte[0]);
            TreeSet<SourceScenario> catalog = schedule.selectedPredictions().stream()
                    .flatMap(item -> item.prediction().predictions().stream())
                    .map(ScenarioPrediction::scenario)
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (catalog.isEmpty()) {
                schedule.runs().stream().map(ScheduledRun::scenario).forEach(catalog::add);
            }
            read(temp, catalog,
                    schedule.trainingRunId(), 0L, "0".repeat(40), false,
                    schedule.runs().isEmpty() ? BenchmarkExecutionConfig.defaults()
                            : config(schedule.runs().getFirst().parameters()),
                    false);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("Atomic schedule publication is required", error);
            }
            return target;
        } catch (Throwable error) {
            deleteTree(temp);
            throw error;
        }
    }

    public static IterationSchedule read(Path directory,
            SortedSet<SourceScenario> requiredScenarios, String expectedTrainingRunId,
            long schedulerSeed, String commitSha, boolean dirtyWorkingTree,
            BenchmarkExecutionConfig benchmarkConfig) throws IOException {
        return read(directory, requiredScenarios, expectedTrainingRunId, schedulerSeed, commitSha,
                dirtyWorkingTree, benchmarkConfig, true);
    }

    private static IterationSchedule read(Path directory,
            SortedSet<SourceScenario> requiredScenarios, String expectedTrainingRunId,
            long schedulerSeed, String commitSha, boolean dirtyWorkingTree,
            BenchmarkExecutionConfig benchmarkConfig, boolean verifyIdentity) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        validateInventory(root);
        List<List<String>> runRows = CanonicalCsv.read(root.resolve("runs.csv"));
        requireHeader(runRows, RUN_HEADER);
        ArrayList<ScheduledRun> runs = new ArrayList<>();
        int iteration = -1;
        SourceScenario previousScenario = null;
        Map<SourceScenario, RunRow> runMetadata = new TreeMap<>();
        for (int i = 1; i < runRows.size(); i++) {
            List<String> row = width(runRows.get(i), 15);
            version(row.get(0));
            if (!row.get(1).equals(expectedTrainingRunId)) {
                throw new IllegalArgumentException("Training run ID mismatch");
            }
            int rowIteration = integer(row.get(2));
            if (iteration < 0) {
                iteration = rowIteration;
            } else if (iteration != rowIteration) {
                throw new IllegalArgumentException("Mixed schedule iterations");
            }
            SourceScenario scenario = SourceScenario.parse(row.get(4));
            if (previousScenario != null && scenario.compareTo(previousScenario) <= 0) {
                throw new IllegalArgumentException("Runs are not scenario sorted");
            }
            previousScenario = scenario;
            if (!requiredScenarios.contains(scenario)) {
                throw new IllegalArgumentException("Schedule contains an unexpected scenario");
            }
            BenchmarkParameters parameters = new BenchmarkParameters(integer(row.get(7)),
                    number(row.get(8)), number(row.get(9)), integer(row.get(10)),
                    number(row.get(11)), bool(row.get(12)), row.get(13), seeds(row.get(14)));
            requireConfig(parameters, benchmarkConfig, scenario);
            RunRow metadata = new RunRow(RunKind.valueOf(row.get(3)), scenario, row.get(5),
                    row.get(6), parameters);
            runMetadata.put(scenario, metadata);
        }
        if (iteration < 0 || runMetadata.isEmpty()) {
            throw new IllegalArgumentException("Schedule has no runs");
        }

        Map<SourceScenario, List<ScheduledPolicy>> policies = readPolicies(
                root.resolve("policies.csv"), runMetadata);
        for (RunRow row : runMetadata.values()) {
            List<ScheduledPolicy> scheduled = policies.get(row.scenario());
            if (scheduled == null || scheduled.isEmpty()) {
                throw new IllegalArgumentException("Run has no policies");
            }
            if (verifyIdentity) {
                validateIdentity(expectedTrainingRunId, iteration, row, scheduled, schedulerSeed,
                        commitSha, dirtyWorkingTree);
            }
            runs.add(new ScheduledRun(row.kind(), row.scenario(), row.runId(), row.cohortId(),
                    row.parameters(), scheduled));
        }
        TreeMap<PolicyId, PolicyVector> policyVectors = new TreeMap<>();
        policies.values().forEach(list -> list.forEach(policy ->
                policyVectors.put(policy.policy().id(), policy.policy())));
        List<ScheduledPolicyPrediction> predictions = readPredictions(
                root.resolve("predictions.csv"), requiredScenarios, policyVectors);
        List<ScenarioBudgetReport> budgets = readBudgets(root.resolve("budget-report.csv"),
                policies);
        List<PolicyId> admissions = readAdmissions(root.resolve("carry-admissions.csv"),
                iteration, predictions);
        validateScheduleSemantics(runs, predictions, admissions, budgets, requiredScenarios);
        return new IterationSchedule(expectedTrainingRunId, iteration, runs, predictions,
                admissions, budgets, 0);
    }

    private static void validateInventory(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Schedule must be a non-symlink directory");
        }
        try (var stream = Files.list(root)) {
            List<String> actual = stream.map(path -> path.getFileName().toString()).sorted().toList();
            if (!actual.equals(FILES.stream().sorted().toList())) {
                throw new IllegalArgumentException("Unexpected schedule inventory");
            }
        }
        for (String name : FILES) {
            Path file = root.resolve(name);
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Invalid schedule file " + name);
            }
        }
        if (Files.size(root.resolve("COMPLETE")) != 0) {
            throw new IllegalArgumentException("COMPLETE must be empty");
        }
    }

    private static Map<SourceScenario, List<ScheduledPolicy>> readPolicies(Path file,
            Map<SourceScenario, RunRow> runs) throws IOException {
        List<List<String>> rows = CanonicalCsv.read(file);
        List<String> header = new ArrayList<>(List.of("schema_version", "scenario_id",
                "benchmark_run_id", "schedule_position", "policy_id", "roles"));
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            header.add("weight_%02d_bits".formatted(i));
        }
        requireHeader(rows, header);
        TreeMap<SourceScenario, List<ScheduledPolicy>> result = new TreeMap<>();
        PolicyRegistry registry = new PolicyRegistry();
        SourceScenario previousScenario = null;
        int expectedPosition = 1;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = width(rows.get(i), 34);
            version(row.get(0));
            SourceScenario scenario = SourceScenario.parse(row.get(1));
            RunRow run = runs.get(scenario);
            if (run == null || !run.runId().equals(row.get(2))) {
                throw new IllegalArgumentException("Policy run identity mismatch");
            }
            if (!scenario.equals(previousScenario)) {
                if (previousScenario != null && scenario.compareTo(previousScenario) <= 0) {
                    throw new IllegalArgumentException("Policies are not scenario sorted");
                }
                previousScenario = scenario;
                expectedPosition = 1;
            }
            if (integer(row.get(3)) != expectedPosition++) {
                throw new IllegalArgumentException("Schedule position gap");
            }
            double[] weights = new double[PolicyVector.WIDTH];
            for (int weight = 0; weight < weights.length; weight++) {
                weights[weight] = Double.longBitsToDouble(hex(row.get(6 + weight)));
            }
            PolicyVector policy = registry.register(PolicyVector.of(weights));
            if (!policy.id().equals(PolicyId.parse(row.get(4)))) {
                throw new IllegalArgumentException("Policy ID/raw bits mismatch");
            }
            EnumSet<PolicyRole> roles = roles(row.get(5));
            if (roles.size() != 1) {
                throw new IllegalArgumentException("Phase 3 schedules require one disjoint role");
            }
            List<ScheduledPolicy> list = result.computeIfAbsent(scenario,
                    ignored -> new ArrayList<>());
            if (list.stream().anyMatch(item -> item.policy().id().equals(policy.id()))) {
                throw new IllegalArgumentException("Duplicate scheduled policy");
            }
            list.add(new ScheduledPolicy(integer(row.get(3)), policy, roles));
        }
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return result;
    }

    private static List<ScheduledPolicyPrediction> readPredictions(Path file,
            SortedSet<SourceScenario> required, Map<PolicyId, PolicyVector> policies)
            throws IOException {
        List<List<String>> rows = CanonicalCsv.read(file);
        requireHeader(rows, PREDICTION_HEADER);
        TreeMap<PolicyId, PredictionBuilder> builders = new TreeMap<>();
        PolicyId previousPolicy = null;
        SourceScenario previousScenario = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = width(rows.get(i), 21);
            version(row.get(0));
            PolicyId policy = PolicyId.parse(row.get(1));
            SourceScenario scenario = SourceScenario.parse(row.get(2));
            if (!required.contains(scenario)
                    || previousPolicy != null && (policy.compareTo(previousPolicy) < 0
                    || policy.equals(previousPolicy)
                    && scenario.compareTo(previousScenario) <= 0)) {
                throw new IllegalArgumentException("Predictions are not deterministically sorted");
            }
            previousPolicy = policy;
            previousScenario = scenario;
            PolicyVector vector = policies.get(policy);
            if (vector == null) {
                throw new IllegalArgumentException("Prediction references unscheduled policy");
            }
            PredictionBuilder builder = builders.computeIfAbsent(policy,
                    ignored -> new PredictionBuilder(vector, rawSummary(row),
                            SchedulePolicyOrigin.valueOf(row.get(20))));
            builder.add(row, scenario);
        }
        ArrayList<ScheduledPolicyPrediction> result = new ArrayList<>();
        for (PredictionBuilder builder : builders.values()) {
            result.add(builder.build(required));
        }
        return List.copyOf(result);
    }

    private static List<ScenarioBudgetReport> readBudgets(Path file,
            Map<SourceScenario, List<ScheduledPolicy>> policies) throws IOException {
        List<List<String>> rows = CanonicalCsv.read(file);
        requireHeader(rows, BUDGET_HEADER);
        ArrayList<ScenarioBudgetReport> result = new ArrayList<>();
        SourceScenario previous = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = width(rows.get(i), 17);
            version(row.get(0));
            SourceScenario scenario = SourceScenario.parse(row.get(1));
            if (previous != null && scenario.compareTo(previous) <= 0) {
                throw new IllegalArgumentException("Budget reports are not scenario sorted");
            }
            previous = scenario;
            ScenarioBudgetReport report = new ScenarioBudgetReport(scenario, integer(row.get(2)),
                    integer(row.get(3)), integer(row.get(4)), integer(row.get(5)),
                    integer(row.get(6)), integer(row.get(7)), integer(row.get(8)),
                    integer(row.get(9)), integer(row.get(10)), integer(row.get(11)),
                    integer(row.get(12)), integer(row.get(13)), integer(row.get(14)),
                    integer(row.get(15)), integer(row.get(16)));
            validateBudget(report, policies.get(scenario));
            result.add(report);
        }
        return List.copyOf(result);
    }

    private static List<PolicyId> readAdmissions(Path file, int iteration,
            List<ScheduledPolicyPrediction> predictions) throws IOException {
        List<List<String>> rows = CanonicalCsv.read(file);
        requireHeader(rows, ADMISSION_HEADER);
        Map<PolicyId, PredictedPolicySummary> summaries = predictions.stream().collect(
                java.util.stream.Collectors.toMap(item -> item.policy().id(),
                        ScheduledPolicyPrediction::prediction));
        ArrayList<PolicyId> result = new ArrayList<>();
        PredictedPolicySummary previous = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = width(rows.get(i), 4);
            version(row.get(0));
            if (integer(row.get(1)) != i || integer(row.get(3)) != iteration) {
                throw new IllegalArgumentException("Invalid carry admission rank");
            }
            PolicyId policy = PolicyId.parse(row.get(2));
            PredictedPolicySummary summary = summaries.get(policy);
            if (summary == null || previous != null
                    && PredictedPolicyComparator.BEST_FIRST.compare(previous, summary) > 0
                    || !result.add(policy)) {
                throw new IllegalArgumentException("Invalid carry admission order");
            }
            previous = summary;
        }
        return List.copyOf(result);
    }

    private static void validateIdentity(String trainingRunId, int iteration, RunRow row,
            List<ScheduledPolicy> policies, long schedulerSeed, String commitSha,
            boolean dirtyWorkingTree) {
        List<RoleIdentity> identities = policies.stream().map(policy -> new RoleIdentity(
                policy.policy().id(), policy.roles().iterator().next())).toList();
        String cohort = SchedulerSeeds.candidateCohortId(trainingRunId, row.kind().name(),
                iteration, row.scenario(), identities, schedulerSeed);
        if (!cohort.equals(row.cohortId())) {
            throw new IllegalArgumentException("Candidate cohort ID mismatch");
        }
        String run = SchedulerSeeds.benchmarkRunId(trainingRunId, row.kind().name(), iteration,
                row.scenario(), cohort, row.parameters(), commitSha, dirtyWorkingTree,
                schedulerSeed);
        if (!run.equals(row.runId())) {
            throw new IllegalArgumentException("Benchmark run ID mismatch");
        }
        if (row.kind() == RunKind.NORMAL) {
            validateTrialOrder(policies, cohort, schedulerSeed);
        }
        for (int i = 0; i < row.scenario().sourceCount(); i++) {
            if (!SchedulerSeeds.frameSourceSeed(run, i, schedulerSeed)
                    .equals(row.parameters().frameSourceSeeds().get(i))) {
                throw new IllegalArgumentException("Frame source seed mismatch");
            }
        }
    }

    private static void validateTrialOrder(List<ScheduledPolicy> policies, String cohort,
            long schedulerSeed) {
        List<ScheduledPolicy> anchors = policies.stream()
                .filter(policy -> policy.roles().contains(PolicyRole.FIXED_ANCHOR))
                .sorted(Comparator.comparing(policy -> policy.policy().id())).toList();
        int budget = policies.size();
        Set<Integer> anchorPositions = new HashSet<>();
        for (int i = 0; i < anchors.size(); i++) {
            int expected = Math.toIntExact(Math.floorDiv(
                    Math.multiplyExact(2L * i + 1L, budget), 2L * anchors.size())) + 1;
            ScheduledPolicy actual = policies.get(expected - 1);
            if (!actual.policy().id().equals(anchors.get(i).policy().id())) {
                throw new IllegalArgumentException("Fixed anchor midpoint mismatch");
            }
            anchorPositions.add(expected);
        }
        ScheduledPolicy previous = null;
        long previousKey = 0L;
        for (ScheduledPolicy policy : policies) {
            if (anchorPositions.contains(policy.schedulePosition())) {
                continue;
            }
            long key = SchedulerSeeds.trialOrderKey(cohort, policy.policy().id(),
                    schedulerSeed);
            if (previous != null) {
                int result = Long.compareUnsigned(previousKey, key);
                if (result > 0 || result == 0
                        && previous.policy().id().compareTo(policy.policy().id()) >= 0) {
                    throw new IllegalArgumentException("Non-anchor trial order mismatch");
                }
            }
            previous = policy;
            previousKey = key;
        }
    }

    private static void validateScheduleSemantics(List<ScheduledRun> runs,
            List<ScheduledPolicyPrediction> predictions, List<PolicyId> admissions,
            List<ScenarioBudgetReport> budgets, SortedSet<SourceScenario> required) {
        if (runs.size() != budgets.size()) {
            throw new IllegalArgumentException("Run/budget scenario mismatch");
        }
        Set<PolicyId> predicted = predictions.stream().map(item -> item.policy().id())
                .collect(java.util.stream.Collectors.toSet());
        for (ScheduledRun run : runs) {
            for (ScheduledPolicy policy : run.policies()) {
                if (run.runKind() != RunKind.BOOTSTRAP
                        && !policy.roles().contains(PolicyRole.FIXED_ANCHOR)
                        && !predicted.contains(policy.policy().id())) {
                    throw new IllegalArgumentException("Scheduled non-anchor lacks prediction");
                }
            }
        }
        if (!predicted.containsAll(admissions)) {
            throw new IllegalArgumentException("Carry admission lacks prediction");
        }
        for (ScheduledPolicyPrediction prediction : predictions) {
            if (!prediction.prediction().predictions().stream()
                    .map(ScenarioPrediction::scenario)
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                    .equals(required)) {
                throw new IllegalArgumentException("Prediction catalog mismatch");
            }
        }
    }

    private static void validateBudget(ScenarioBudgetReport report,
            List<ScheduledPolicy> policies) {
        if (policies == null || report.totalAssigned() != report.candidateBudget()
                || policies.size() != report.candidateBudget()
                || count(policies, PolicyRole.FIXED_ANCHOR) != report.fixedAssigned()
                || count(policies, PolicyRole.CARRY_FORWARD) != report.carryAssigned()
                || count(policies, PolicyRole.LEADER_REVALIDATION) != report.leaderAssigned()
                || count(policies, PolicyRole.DISAGREEMENT_AUDIT) != report.auditAssigned()
                || count(policies, PolicyRole.EXPLORATION) != report.explorationAssigned()
                || report.fixedAssigned() != report.fixedRequested()
                || report.explorationAssigned() != report.explorationRequested()
                + report.carryTransferredToExploration()
                + report.leaderTransferredToExploration()
                + report.auditTransferredToExploration()
                || report.carryRequested() - report.carryAssigned()
                != report.carryTransferredToExploration()
                || report.leaderRequested() - report.leaderAssigned()
                != report.leaderTransferredToExploration()
                || report.auditRequested() - report.auditAssigned()
                != report.auditTransferredToExploration()) {
            throw new IllegalArgumentException("Budget report disagrees with schedule");
        }
    }

    private static int count(List<ScheduledPolicy> policies, PolicyRole role) {
        return Math.toIntExact(policies.stream().filter(policy ->
                policy.roles().contains(role)).count());
    }

    private static String runs(IterationSchedule schedule) {
        StringBuilder out = new StringBuilder(CanonicalCsv.row(RUN_HEADER));
        for (ScheduledRun run : schedule.runs().stream()
                .sorted(Comparator.comparing(ScheduledRun::scenario)).toList()) {
            BenchmarkParameters p = run.parameters();
            out.append(CanonicalCsv.row(List.of("1", schedule.trainingRunId(),
                    Integer.toString(schedule.iteration()), run.runKind().name(),
                    run.scenario().canonical(), run.benchmarkRunId(), run.candidateCohortId(),
                    Integer.toString(p.expectedRepetitions()),
                    Long.toString(p.sampleDurationNanos()),
                    Long.toString(p.livenessTimeoutNanos()),
                    Integer.toString(p.framesPerSource()),
                    Long.toString(p.resetTimeoutNanos()), Boolean.toString(p.orderedFrames()),
                    p.cpuSetHex(), encodeSeeds(p.frameSourceSeeds()))));
        }
        return out.toString();
    }

    private static String policies(IterationSchedule schedule) {
        List<String> header = new ArrayList<>(List.of("schema_version", "scenario_id",
                "benchmark_run_id", "schedule_position", "policy_id", "roles"));
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            header.add("weight_%02d_bits".formatted(i));
        }
        StringBuilder out = new StringBuilder(CanonicalCsv.row(header));
        for (ScheduledRun run : schedule.runs().stream()
                .sorted(Comparator.comparing(ScheduledRun::scenario)).toList()) {
            for (ScheduledPolicy policy : run.policies()) {
                List<String> row = new ArrayList<>(List.of("1", run.scenario().canonical(),
                        run.benchmarkRunId(), Integer.toString(policy.schedulePosition()),
                        policy.policy().id().canonical(), policy.roles().stream()
                        .map(Enum::name).sorted().reduce((a, b) -> a + ";" + b).orElseThrow()));
                for (double weight : policy.policy().copyWeights()) {
                    row.add("%016x".formatted(Double.doubleToRawLongBits(weight)));
                }
                out.append(CanonicalCsv.row(row));
            }
        }
        return out.toString();
    }

    private static String predictions(IterationSchedule schedule) {
        StringBuilder out = new StringBuilder(CanonicalCsv.row(PREDICTION_HEADER));
        for (ScheduledPolicyPrediction item : schedule.selectedPredictions().stream()
                .sorted(Comparator.comparing(prediction -> prediction.policy().id())).toList()) {
            PredictedPolicySummary summary = item.prediction();
            for (ScenarioPrediction scenario : summary.predictions()) {
                out.append(CanonicalCsv.row(List.of("1", item.policy().id().canonical(),
                        scenario.scenario().canonical(),
                        Double.toString(scenario.predictedQuality()),
                        Double.toString(scenario.ordinalStdDev()),
                        Double.toString(scenario.qualityIntervalLow()),
                        Double.toString(scenario.qualityIntervalHigh()),
                        Double.toString(scenario.ordinalEntropy()),
                        Double.toString(scenario.topDecileProbability()),
                        Double.toString(scenario.epistemicStdDev()),
                        Double.toString(scenario.disagreementRange()),
                        Double.toString(summary.predictedWorstQuality()),
                        Double.toString(summary.predictedQualityP25()),
                        Double.toString(summary.predictedGeometricMeanQuality()),
                        Double.toString(summary.predictedQualityMad()),
                        Double.toString(summary.maximumEpistemicStdDev()),
                        Double.toString(summary.maximumDisagreementRange()),
                        Double.toString(summary.meanOrdinalStdDev()),
                        Double.toString(summary.meanOrdinalEntropy()),
                        Double.toString(summary.pessimisticQuality()), item.origin().name())));
            }
        }
        return out.toString();
    }

    private static String budgets(IterationSchedule schedule) {
        StringBuilder out = new StringBuilder(CanonicalCsv.row(BUDGET_HEADER));
        for (ScenarioBudgetReport b : schedule.budgetReports().stream()
                .sorted(Comparator.comparing(ScenarioBudgetReport::scenario)).toList()) {
            out.append(CanonicalCsv.row(List.of("1", b.scenario().canonical(),
                    Integer.toString(b.candidateBudget()),
                    Integer.toString(b.fixedRequested()), Integer.toString(b.fixedAssigned()),
                    Integer.toString(b.carryRequested()), Integer.toString(b.carryAssigned()),
                    Integer.toString(b.leaderRequested()), Integer.toString(b.leaderAssigned()),
                    Integer.toString(b.auditRequested()), Integer.toString(b.auditAssigned()),
                    Integer.toString(b.explorationRequested()),
                    Integer.toString(b.explorationAssigned()),
                    Integer.toString(b.carryTransferredToExploration()),
                    Integer.toString(b.leaderTransferredToExploration()),
                    Integer.toString(b.auditTransferredToExploration()),
                    Integer.toString(b.totalAssigned()))));
        }
        return out.toString();
    }

    private static String admissions(IterationSchedule schedule) {
        StringBuilder out = new StringBuilder(CanonicalCsv.row(ADMISSION_HEADER));
        for (int i = 0; i < schedule.carryAdmissions().size(); i++) {
            out.append(CanonicalCsv.row(List.of("1", Integer.toString(i + 1),
                    schedule.carryAdmissions().get(i).canonical(),
                    Integer.toString(schedule.iteration()))));
        }
        return out.toString();
    }

    private static String encodeSeeds(List<FrameSourceSeed> seeds) {
        return seeds.stream().map(seed -> seed.sourceIndex() + ":"
                + "%016x".formatted(seed.idHash()) + ":"
                + "%016x".formatted(seed.routingSeed()))
                .reduce((left, right) -> left + ";" + right).orElse("");
    }

    private static List<FrameSourceSeed> seeds(String value) {
        ArrayList<FrameSourceSeed> result = new ArrayList<>();
        if (!value.isEmpty()) {
            for (String item : value.split(";")) {
                String[] fields = item.split(":", -1);
                if (fields.length != 3) {
                    throw new IllegalArgumentException("Invalid frame seed");
                }
                result.add(new FrameSourceSeed(integer(fields[0]), hex(fields[1]),
                        hex(fields[2])));
            }
        }
        return List.copyOf(result);
    }

    private static void requireConfig(BenchmarkParameters parameters,
            BenchmarkExecutionConfig config, SourceScenario scenario) {
        if (parameters.expectedRepetitions() != config.expectedRepetitions()
                || parameters.sampleDurationNanos() != config.sampleDurationNanos()
                || parameters.livenessTimeoutNanos() != config.livenessTimeoutNanos()
                || parameters.framesPerSource() != config.framesPerSource()
                || parameters.resetTimeoutNanos() != config.resetTimeoutNanos()
                || parameters.orderedFrames() != config.orderedFrames()
                || parameters.frameSourceSeeds().size() != scenario.sourceCount()) {
            throw new IllegalArgumentException("Benchmark configuration mismatch");
        }
    }

    private static BenchmarkExecutionConfig config(BenchmarkParameters parameters) {
        return new BenchmarkExecutionConfig(parameters.expectedRepetitions(),
                parameters.sampleDurationNanos(), parameters.livenessTimeoutNanos(),
                parameters.framesPerSource(), parameters.resetTimeoutNanos(),
                parameters.orderedFrames());
    }

    private static double[] rawSummary(List<String> row) {
        double[] result = new double[9];
        for (int i = 0; i < result.length; i++) {
            result[i] = finite(row.get(11 + i));
        }
        return result;
    }

    private static void requireHeader(List<List<String>> rows, List<String> expected) {
        if (rows.isEmpty() || !rows.getFirst().equals(expected)) {
            throw new IllegalArgumentException("Unexpected schedule CSV header");
        }
    }

    private static List<String> width(List<String> row, int expected) {
        if (row.size() != expected) {
            throw new IllegalArgumentException("Unexpected schedule CSV row width");
        }
        return row;
    }

    private static void version(String value) {
        if (!value.equals("1")) {
            throw new IllegalArgumentException("Unsupported schedule schema");
        }
    }

    private static int integer(String value) {
        return Integer.parseInt(value);
    }

    private static long number(String value) {
        return Long.parseLong(value);
    }

    private static long hex(String value) {
        if (!value.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Invalid hexadecimal value");
        }
        return Long.parseUnsignedLong(value, 16);
    }

    private static boolean bool(String value) {
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException("Invalid boolean");
        }
        return Boolean.parseBoolean(value);
    }

    private static double finite(String value) {
        double result = Double.parseDouble(value);
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("Non-finite prediction");
        }
        return result;
    }

    private static EnumSet<PolicyRole> roles(String value) {
        EnumSet<PolicyRole> result = EnumSet.noneOf(PolicyRole.class);
        for (String role : value.split(";")) {
            if (!result.add(PolicyRole.valueOf(role))) {
                throw new IllegalArgumentException("Duplicate role");
            }
        }
        String canonical = result.stream().map(Enum::name).sorted()
                .reduce((left, right) -> left + ";" + right).orElseThrow();
        if (!canonical.equals(value)) {
            throw new IllegalArgumentException("Roles are not sorted");
        }
        return result;
    }

    private static void deleteTree(Path directory) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Preserve the original publication failure.
        }
    }

    private record RunRow(RunKind kind, SourceScenario scenario, String runId,
            String cohortId, BenchmarkParameters parameters) {
    }

    private record RoleIdentity(PolicyId policyId, PolicyRole role)
            implements SchedulerSeeds.PolicyWithRole {
    }

    private static final class PredictionBuilder {
        private final PolicyVector policy;
        private final double[] summary;
        private final SchedulePolicyOrigin origin;
        private final List<ScenarioPrediction> scenarios = new ArrayList<>();

        private PredictionBuilder(PolicyVector policy, double[] summary,
                SchedulePolicyOrigin origin) {
            this.policy = policy;
            this.summary = summary;
            this.origin = origin;
        }

        private void add(List<String> row, SourceScenario scenario) {
            if (!java.util.Arrays.equals(summary, rawSummary(row))
                    || origin != SchedulePolicyOrigin.valueOf(row.get(20))) {
                throw new IllegalArgumentException("Repeated prediction summary mismatch");
            }
            scenarios.add(new ScenarioPrediction(scenario, finite(row.get(3)),
                    finite(row.get(4)), finite(row.get(5)), finite(row.get(6)),
                    finite(row.get(7)), finite(row.get(8)), finite(row.get(9)),
                    finite(row.get(10))));
        }

        private ScheduledPolicyPrediction build(SortedSet<SourceScenario> required) {
            if (scenarios.size() != required.size()
                    || !scenarios.stream().map(ScenarioPrediction::scenario)
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                    .equals(required)) {
                throw new IllegalArgumentException("Incomplete prediction grid");
            }
            PredictedPolicySummary recomputed = PredictedPolicyRanker.summarize(
                    new PolicyPredictionCurve(policy, scenarios), required);
            double[] actual = {recomputed.predictedWorstQuality(),
                    recomputed.predictedQualityP25(),
                    recomputed.predictedGeometricMeanQuality(),
                    recomputed.predictedQualityMad(),
                    recomputed.maximumEpistemicStdDev(),
                    recomputed.maximumDisagreementRange(),
                    recomputed.meanOrdinalStdDev(), recomputed.meanOrdinalEntropy(),
                    recomputed.pessimisticQuality()};
            if (!java.util.Arrays.equals(summary, actual)) {
                throw new IllegalArgumentException("Prediction summary does not recompute");
            }
            return new ScheduledPolicyPrediction(policy, recomputed, origin);
        }
    }

    private ScheduleCodec() {
    }
}
