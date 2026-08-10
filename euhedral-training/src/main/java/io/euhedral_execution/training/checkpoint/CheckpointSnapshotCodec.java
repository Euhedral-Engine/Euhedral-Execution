package io.euhedral_execution.training.checkpoint;

import io.euhedral_execution.training.checkpoint.data.ArtifactReference;
import io.euhedral_execution.training.checkpoint.data.ClosedLoopCheckpoint;
import io.euhedral_execution.training.checkpoint.data.EvidenceIndexEntry;
import io.euhedral_execution.training.checkpoint.data.LoadedCheckpoint;
import io.euhedral_execution.training.checkpoint.data.PendingBenchmarkRun;
import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.checkpoint.enums.EvidenceSource;
import io.euhedral_execution.training.checkpoint.enums.PendingRunStatus;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.data.io.ObservationBundle;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.scheduling.data.CarryForwardEntry;
import io.euhedral_execution.training.scheduling.data.CarryScenarioState;
import io.euhedral_execution.training.scheduling.data.RotationGroup;
import io.euhedral_execution.training.scheduling.enums.CoverageState;
import io.euhedral_execution.training.scheduling.enums.RunKind;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

public final class CheckpointSnapshotCodec {
    private static final String ARTIFACT_TYPE = "euhedral-optimizer-checkpoint";
    private static final List<String> SIDECARS = List.of(
            "required-scenarios.csv",
            "rotation-cursors.csv",
            "evidence-index.csv",
            "carry-forward.csv",
            "carry-forward-scenarios.csv",
            "pending-runs.csv");
    private static final List<String> INVENTORY = List.of(
            "state.csv",
            "required-scenarios.csv",
            "rotation-cursors.csv",
            "evidence-index.csv",
            "carry-forward.csv",
            "carry-forward-scenarios.csv",
            "pending-runs.csv",
            "COMPLETE");
    private static final List<String> STATE_HEADER = List.of(
            "schema_version",
            "artifact_type",
            "training_run_id",
            "revision",
            "stage",
            "next_iteration",
            "sobol_cursor",
            "config_sha256",
            "required_scenarios_sha256",
            "rotation_cursors_sha256",
            "evidence_index_sha256",
            "carry_forward_sha256",
            "carry_forward_scenarios_sha256",
            "pending_runs_sha256",
            "anchor_set_id",
            "calibration_plan_path",
            "calibration_plan_sha256",
            "latest_merge_path",
            "latest_merge_sha256",
            "latest_model_path",
            "latest_model_sha256",
            "pending_schedule_path",
            "pending_schedule_sha256");

    private CheckpointSnapshotCodec() {}

    public static Optional<LoadedCheckpoint> loadLatest(
            Path workspace, String expectedTrainingRunId, String expectedConfigSha256) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path checkpoints = root.resolve("checkpoints");
        if (!Files.isDirectory(checkpoints, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        List<Path> complete;
        try (var stream = Files.list(checkpoints)) {
            complete = stream.filter(path -> path.getFileName().toString().matches("checkpoint-[0-9]{8}"))
                    .filter(path -> Files.isRegularFile(path.resolve("COMPLETE"), LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList();
        }
        if (complete.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < complete.size(); i++) {
            int revision = revision(complete.get(i));
            if (revision != i + 1) {
                throw new IllegalArgumentException("Checkpoint revisions are not contiguous");
            }
        }
        Path latest = complete.getLast();
        ClosedLoopCheckpoint checkpoint = read(root, latest);
        if (!checkpoint.trainingRunId().equals(expectedTrainingRunId)) {
            throw new IllegalArgumentException("Checkpoint training run ID mismatch");
        }
        if (!checkpoint.configSha256().equals(expectedConfigSha256)) {
            throw new IllegalArgumentException("Checkpoint frozen configuration mismatch");
        }
        return Optional.of(new LoadedCheckpoint(latest, checkpoint));
    }

    public static LoadedCheckpoint loadRevision(Path workspace, int requestedRevision) throws IOException {
        if (requestedRevision <= 0) {
            throw new IllegalArgumentException("Checkpoint revision must be positive");
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path checkpoints = root.resolve("checkpoints");
        if (!Files.isDirectory(checkpoints, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Checkpoint directory is absent");
        }
        List<Path> complete = completeCheckpoints(checkpoints);
        if (complete.size() < requestedRevision) {
            throw new IllegalArgumentException("Checkpoint revision is absent");
        }
        for (int index = 0; index < complete.size(); index++) {
            if (revision(complete.get(index)) != index + 1) {
                throw new IllegalArgumentException("Checkpoint revisions are not contiguous");
            }
        }
        Path selected = complete.get(requestedRevision - 1);
        return new LoadedCheckpoint(selected, read(root, selected));
    }

    public static ClosedLoopCheckpoint readDetachedForAudit(Path checkpointDirectory) throws IOException {
        Path directory = checkpointDirectory.toAbsolutePath().normalize();
        return read(directory.getParent() == null ? directory : directory.getParent(), directory, false);
    }

    public static LoadedCheckpoint writeNext(Path workspace, ClosedLoopCheckpoint checkpoint) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path checkpoints = root.resolve("checkpoints");
        Files.createDirectories(checkpoints);
        validatePrevious(root, checkpoint);
        Path target = checkpoints.resolve("checkpoint-%08d".formatted(checkpoint.revision()));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Checkpoint revision already exists");
        }
        Path temp = checkpoints.resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createDirectory(temp);
        try {
            Map<String, String> contents = sidecars(checkpoint);
            for (String name : SIDECARS) {
                writeForced(temp.resolve(name), contents.get(name));
            }
            writeForced(temp.resolve("state.csv"), state(checkpoint, temp));
            writeForced(temp.resolve("COMPLETE"), "");
            ClosedLoopCheckpoint readBack = read(root, temp);
            if (!readBack.equals(checkpoint)) {
                throw new IllegalStateException("Checkpoint read-back mismatch");
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("Atomic checkpoint publication is required", error);
            }
            return new LoadedCheckpoint(target, checkpoint);
        } catch (Throwable error) {
            deleteTree(temp);
            throw error;
        }
    }

    private static void validatePrevious(Path workspace, ClosedLoopCheckpoint next) throws IOException {
        Path checkpoints = workspace.resolve("checkpoints");
        List<Path> complete;
        try (var stream = Files.list(checkpoints)) {
            complete = stream.filter(path -> path.getFileName().toString().matches("checkpoint-[0-9]{8}"))
                    .filter(path -> Files.isRegularFile(path.resolve("COMPLETE")))
                    .sorted()
                    .toList();
        }
        if (complete.isEmpty()) {
            if (next.revision() != 1
                    || next.stage() != CheckpointStage.BOOTSTRAP_PENDING
                            && next.stage() != CheckpointStage.READY_TO_TRAIN) {
                throw new IllegalArgumentException("Invalid initial checkpoint");
            }
            return;
        }
        ClosedLoopCheckpoint previous = read(workspace, complete.getLast());
        if (next.revision() != previous.revision() + 1
                || !next.trainingRunId().equals(previous.trainingRunId())
                || !next.configSha256().equals(previous.configSha256())
                || !next.requiredScenarios().equals(previous.requiredScenarios())
                || !validTransition(previous.stage(), next.stage())) {
            throw new IllegalArgumentException("Invalid checkpoint transition");
        }
        if (next.sobolCursor() != previous.sobolCursor() && next.stage() != CheckpointStage.SCHEDULE_READY) {
            throw new IllegalArgumentException("Sobol cursor changed without schedule publication");
        }
    }

    private static boolean validTransition(CheckpointStage from, CheckpointStage to) {
        return switch (from) {
            case BOOTSTRAP_PENDING -> to == CheckpointStage.BOOTSTRAP_PENDING || to == CheckpointStage.READY_TO_TRAIN;
            case READY_TO_TRAIN -> to == CheckpointStage.MODEL_READY || to == CheckpointStage.MODEL_REJECTED;
            case MODEL_READY -> to == CheckpointStage.SCHEDULE_READY;
            case MODEL_REJECTED -> to == CheckpointStage.SCHEDULE_READY;
            case SCHEDULE_READY -> to == CheckpointStage.BENCHMARKING;
            case BENCHMARKING -> to == CheckpointStage.BENCHMARKING || to == CheckpointStage.READY_TO_MERGE;
            case READY_TO_MERGE -> to == CheckpointStage.READY_TO_TRAIN || to == CheckpointStage.RUN_COMPLETE;
            case RUN_COMPLETE -> false;
        };
    }

    private static Map<String, String> sidecars(ClosedLoopCheckpoint checkpoint) {
        Map<String, String> result = new HashMap<>();
        result.put("required-scenarios.csv", requiredScenarios(checkpoint));
        result.put("rotation-cursors.csv", rotationCursors(checkpoint));
        result.put("evidence-index.csv", evidence(checkpoint));
        result.put("carry-forward.csv", carry(checkpoint));
        result.put("carry-forward-scenarios.csv", carryScenarios(checkpoint));
        result.put("pending-runs.csv", pending(checkpoint));
        return result;
    }

    private static String state(ClosedLoopCheckpoint checkpoint, Path directory) throws IOException {
        List<String> row = new ArrayList<>(List.of(
                "1",
                ARTIFACT_TYPE,
                checkpoint.trainingRunId(),
                Integer.toString(checkpoint.revision()),
                checkpoint.stage().name(),
                Integer.toString(checkpoint.nextIteration()),
                Long.toString(checkpoint.sobolCursor()),
                checkpoint.configSha256()));
        for (String sidecar : SIDECARS) {
            row.add(fileSha256(directory.resolve(sidecar)));
        }
        row.add(checkpoint.anchorSetId().orElse(""));
        appendArtifact(row, checkpoint.calibrationPlan());
        appendArtifact(row, checkpoint.latestMerge());
        appendArtifact(row, checkpoint.latestModel());
        appendArtifact(row, checkpoint.pendingSchedule());
        return CanonicalCsv.row(STATE_HEADER) + CanonicalCsv.row(row);
    }

    private static String requiredScenarios(ClosedLoopCheckpoint checkpoint) {
        StringBuilder out = new StringBuilder(CanonicalCsv.row(List.of(
                "schema_version",
                "scenario_id",
                "environment_id",
                "source_count",
                "available_physical_core_count",
                "source_ratio_numerator",
                "source_ratio_denominator")));
        for (SourceScenario scenario : checkpoint.requiredScenarios()) {
            out.append(CanonicalCsv.row(List.of(
                    "1",
                    scenario.canonical(),
                    scenario.environmentId(),
                    Integer.toString(scenario.sourceCount()),
                    Integer.toString(scenario.availablePhysicalCoreCount()),
                    Integer.toString(scenario.ratio().numerator()),
                    Integer.toString(scenario.ratio().denominator()))));
        }
        return out.toString();
    }

    private static String rotationCursors(ClosedLoopCheckpoint checkpoint) {
        StringBuilder out = new StringBuilder(CanonicalCsv.row(
                List.of("schema_version", "environment_id", "available_physical_core_count", "next_index")));
        checkpoint
                .rotationCursors()
                .forEach((group, index) -> out.append(CanonicalCsv.row(List.of(
                        "1",
                        group.environmentId(),
                        Integer.toString(group.availablePhysicalCoreCount()),
                        Integer.toString(index)))));
        return out.toString();
    }

    private static String evidence(ClosedLoopCheckpoint checkpoint) {
        StringBuilder out = new StringBuilder(CanonicalCsv.row(List.of(
                "schema_version", "benchmark_run_id", "scenario_id", "evidence_path", "evidence_sha256", "source")));
        checkpoint.evidence().stream()
                .sorted(Comparator.comparing(EvidenceIndexEntry::benchmarkRunId))
                .forEach(row -> out.append(CanonicalCsv.row(List.of(
                        "1",
                        row.benchmarkRunId(),
                        row.scenario().canonical(),
                        row.bundle().relativePath(),
                        row.bundle().sha256(),
                        row.source().name()))));
        return out.toString();
    }

    private static String carry(ClosedLoopCheckpoint checkpoint) {
        List<String> header = new ArrayList<>(List.of(
                "schema_version",
                "policy_id",
                "first_seen_iteration",
                "last_updated_iteration",
                "valid_required_scenario_count",
                "observed_required_scenario_count",
                "pessimistic_missing_quality",
                "maximum_missing_epistemic_stddev",
                "maximum_missing_disagreement_range"));
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            header.add("weight_%02d_bits".formatted(i));
        }
        StringBuilder out = new StringBuilder(CanonicalCsv.row(header));
        checkpoint.carryForward().stream()
                .sorted(Comparator.comparing(entry -> entry.policy().id()))
                .forEach(entry -> {
                    List<String> row = new ArrayList<>(List.of(
                            "1",
                            entry.policy().id().canonical(),
                            Integer.toString(entry.firstSeenIteration()),
                            Integer.toString(entry.lastUpdatedIteration()),
                            Integer.toString(entry.validScenarioCount()),
                            Integer.toString(observedCount(entry)),
                            Double.toString(pessimistic(entry)),
                            Double.toString(maxEpistemic(entry)),
                            Double.toString(maxDisagreement(entry))));
                    for (double weight : entry.policy().copyWeights()) {
                        row.add("%016x".formatted(Double.doubleToRawLongBits(weight)));
                    }
                    out.append(CanonicalCsv.row(row));
                });
        return out.toString();
    }

    private static String carryScenarios(ClosedLoopCheckpoint checkpoint) {
        StringBuilder out = new StringBuilder(CanonicalCsv.row(List.of(
                "schema_version",
                "policy_id",
                "scenario_id",
                "coverage_status",
                "attempt_count",
                "last_attempt_iteration",
                "next_eligible_iteration",
                "predicted_quality",
                "ordinal_stddev",
                "quality_interval_low",
                "quality_interval_high",
                "ordinal_entropy",
                "top_decile_probability",
                "epistemic_stddev",
                "disagreement_range")));
        checkpoint.carryForward().stream()
                .sorted(Comparator.comparing(entry -> entry.policy().id()))
                .forEach(entry -> entry.scenarios().forEach((scenario, state) -> {
                    ScenarioPrediction prediction = state.prediction();
                    out.append(CanonicalCsv.row(List.of(
                            "1",
                            entry.policy().id().canonical(),
                            scenario.canonical(),
                            state.coverage().name(),
                            Integer.toString(state.attemptCount()),
                            state.lastAttemptIteration().isPresent()
                                    ? Integer.toString(
                                            state.lastAttemptIteration().getAsInt())
                                    : "",
                            Integer.toString(state.nextEligibleIteration()),
                            Double.toString(prediction.predictedQuality()),
                            Double.toString(prediction.ordinalStdDev()),
                            Double.toString(prediction.qualityIntervalLow()),
                            Double.toString(prediction.qualityIntervalHigh()),
                            Double.toString(prediction.ordinalEntropy()),
                            Double.toString(prediction.topDecileProbability()),
                            Double.toString(prediction.epistemicStdDev()),
                            Double.toString(prediction.disagreementRange()))));
                }));
        return out.toString();
    }

    private static String pending(ClosedLoopCheckpoint checkpoint) {
        StringBuilder out = new StringBuilder(CanonicalCsv.row(List.of(
                "schema_version",
                "iteration",
                "run_kind",
                "scenario_id",
                "benchmark_run_id",
                "candidate_cohort_id",
                "schedule_path",
                "schedule_sha256",
                "evidence_path",
                "status")));
        checkpoint.pendingRuns().stream()
                .sorted(Comparator.comparing(PendingBenchmarkRun::scenario))
                .forEach(row -> out.append(CanonicalCsv.row(List.of(
                        "1",
                        Integer.toString(row.iteration()),
                        row.runKind().name(),
                        row.scenario().canonical(),
                        row.benchmarkRunId(),
                        row.candidateCohortId(),
                        row.schedule().relativePath(),
                        row.schedule().sha256(),
                        row.evidenceRelativePath(),
                        row.status().name()))));
        return out.toString();
    }

    private static ClosedLoopCheckpoint read(Path workspace, Path directory) {
        return read(workspace, directory, true);
    }

    private static ClosedLoopCheckpoint read(Path workspace, Path directory, boolean dereferenceArtifacts) {
        try {
            validateInventory(directory);
            List<List<String>> stateRows = CanonicalCsv.read(directory.resolve("state.csv"));
            if (stateRows.size() != 2
                    || !stateRows.getFirst().equals(STATE_HEADER)
                    || stateRows.get(1).size() != 23) {
                throw new IllegalArgumentException("Invalid checkpoint state.csv");
            }
            List<String> row = stateRows.get(1);
            version(row.get(0));
            if (!row.get(1).equals(ARTIFACT_TYPE)) {
                throw new IllegalArgumentException("Checkpoint artifact type mismatch");
            }
            for (int i = 0; i < SIDECARS.size(); i++) {
                if (!row.get(8 + i).equals(fileSha256(directory.resolve(SIDECARS.get(i))))) {
                    throw new IllegalArgumentException("Checkpoint sidecar checksum mismatch");
                }
            }
            TreeSet<SourceScenario> scenarios = readScenarios(directory.resolve("required-scenarios.csv"));
            TreeMap<RotationGroup, Integer> cursors = readCursors(directory.resolve("rotation-cursors.csv"));
            List<EvidenceIndexEntry> evidence =
                    readEvidence(workspace, directory.resolve("evidence-index.csv"), dereferenceArtifacts);
            List<CarryForwardEntry> carry = readCarry(
                    directory.resolve("carry-forward.csv"),
                    directory.resolve("carry-forward-scenarios.csv"),
                    scenarios);
            Optional<ArtifactReference> calibration = artifact(row.get(15), row.get(16));
            Optional<ArtifactReference> merge = artifact(row.get(17), row.get(18));
            Optional<ArtifactReference> model = artifact(row.get(19), row.get(20));
            Optional<ArtifactReference> schedule = artifact(row.get(21), row.get(22));
            if (dereferenceArtifacts) {
                validateArtifacts(workspace, List.of(calibration, merge, model, schedule));
            }
            List<PendingBenchmarkRun> pending = readPending(directory.resolve("pending-runs.csv"));
            int revision = integer(row.get(3));
            if (directory.getFileName().toString().matches("checkpoint-[0-9]{8}") && revision != revision(directory)) {
                throw new IllegalArgumentException("Checkpoint directory revision mismatch");
            }
            return new ClosedLoopCheckpoint(
                    1,
                    row.get(2),
                    revision,
                    CheckpointStage.valueOf(row.get(4)),
                    integer(row.get(5)),
                    Long.parseLong(row.get(6)),
                    row.get(7),
                    scenarios,
                    cursors,
                    evidence,
                    carry,
                    row.get(14).isEmpty() ? Optional.empty() : Optional.of(row.get(14)),
                    calibration,
                    merge,
                    model,
                    schedule,
                    pending);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static TreeSet<SourceScenario> readScenarios(Path file) throws IOException {
        List<List<String>> rows = CanonicalCsv.read(file);
        requireHeader(
                rows,
                List.of(
                        "schema_version",
                        "scenario_id",
                        "environment_id",
                        "source_count",
                        "available_physical_core_count",
                        "source_ratio_numerator",
                        "source_ratio_denominator"));
        TreeSet<SourceScenario> result = new TreeSet<>();
        SourceScenario previous = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = width(rows.get(i), 7);
            version(row.get(0));
            SourceScenario scenario = SourceScenario.of(row.get(2), integer(row.get(3)), integer(row.get(4)));
            if (!scenario.canonical().equals(row.get(1))
                    || scenario.ratio().numerator() != integer(row.get(5))
                    || scenario.ratio().denominator() != integer(row.get(6))
                    || previous != null && scenario.compareTo(previous) <= 0
                    || !result.add(scenario)) {
                throw new IllegalArgumentException("Invalid required scenario row");
            }
            previous = scenario;
        }
        return result;
    }

    private static TreeMap<RotationGroup, Integer> readCursors(Path file) throws IOException {
        List<List<String>> rows = CanonicalCsv.read(file);
        requireHeader(rows, List.of("schema_version", "environment_id", "available_physical_core_count", "next_index"));
        TreeMap<RotationGroup, Integer> result = new TreeMap<>();
        RotationGroup previous = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = width(rows.get(i), 4);
            version(row.get(0));
            RotationGroup group = new RotationGroup(row.get(1), integer(row.get(2)));
            if (previous != null && group.compareTo(previous) <= 0 || result.put(group, integer(row.get(3))) != null) {
                throw new IllegalArgumentException("Invalid rotation cursor order");
            }
            previous = group;
        }
        return result;
    }

    private static List<EvidenceIndexEntry> readEvidence(Path workspace, Path file, boolean dereferenceArtifacts)
            throws IOException {
        List<List<String>> rows = CanonicalCsv.read(file);
        requireHeader(
                rows,
                List.of(
                        "schema_version",
                        "benchmark_run_id",
                        "scenario_id",
                        "evidence_path",
                        "evidence_sha256",
                        "source"));
        ArrayList<EvidenceIndexEntry> result = new ArrayList<>();
        String previous = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = width(rows.get(i), 6);
            version(row.get(0));
            if (previous != null && previous.compareTo(row.get(1)) >= 0) {
                throw new IllegalArgumentException("Evidence index is not sorted");
            }
            ArtifactReference bundle = new ArtifactReference(row.get(3), row.get(4));
            Path bundlePath = workspace.resolve(bundle.relativePath()).normalize();
            if (!bundlePath.startsWith(workspace.resolve("evidence"))) {
                throw new IllegalArgumentException("Evidence bundle is outside evidence/");
            }
            SourceScenario scenario = SourceScenario.parse(row.get(2));
            if (dereferenceArtifacts) {
                validateArtifact(workspace, bundle);
                ObservationBundle observationBundle = ObservationBundleReader.read(bundlePath);
                if (!observationBundle.run().descriptor().benchmarkRunId().equals(row.get(1))
                        || !observationBundle.run().descriptor().scenario().equals(scenario)) {
                    throw new IllegalArgumentException("Evidence bundle identity mismatch");
                }
            }
            result.add(new EvidenceIndexEntry(row.get(1), scenario, bundle, EvidenceSource.valueOf(row.get(5))));
            previous = row.get(1);
        }
        return List.copyOf(result);
    }

    private static List<CarryForwardEntry> readCarry(Path summaryFile, Path scenarioFile, Set<SourceScenario> required)
            throws IOException {
        List<List<String>> summaries = CanonicalCsv.read(summaryFile);
        List<String> header = new ArrayList<>(List.of(
                "schema_version",
                "policy_id",
                "first_seen_iteration",
                "last_updated_iteration",
                "valid_required_scenario_count",
                "observed_required_scenario_count",
                "pessimistic_missing_quality",
                "maximum_missing_epistemic_stddev",
                "maximum_missing_disagreement_range"));
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            header.add("weight_%02d_bits".formatted(i));
        }
        requireHeader(summaries, header);
        TreeMap<PolicyId, CarrySummary> metadata = new TreeMap<>();
        PolicyId previousPolicy = null;
        for (int i = 1; i < summaries.size(); i++) {
            List<String> row = width(summaries.get(i), 37);
            version(row.get(0));
            double[] weights = new double[PolicyVector.WIDTH];
            for (int weight = 0; weight < weights.length; weight++) {
                weights[weight] = Double.longBitsToDouble(hex(row.get(9 + weight)));
            }
            PolicyVector policy = PolicyVector.of(weights);
            if (!policy.id().equals(PolicyId.parse(row.get(1)))
                    || previousPolicy != null && policy.id().compareTo(previousPolicy) <= 0
                    || metadata.put(
                                    policy.id(),
                                    new CarrySummary(
                                            policy,
                                            integer(row.get(2)),
                                            integer(row.get(3)),
                                            integer(row.get(4)),
                                            integer(row.get(5)),
                                            finite(row.get(6)),
                                            finite(row.get(7)),
                                            finite(row.get(8))))
                            != null) {
                throw new IllegalArgumentException("Invalid carry summary");
            }
            previousPolicy = policy.id();
        }
        List<List<String>> rows = CanonicalCsv.read(scenarioFile);
        requireHeader(
                rows,
                List.of(
                        "schema_version",
                        "policy_id",
                        "scenario_id",
                        "coverage_status",
                        "attempt_count",
                        "last_attempt_iteration",
                        "next_eligible_iteration",
                        "predicted_quality",
                        "ordinal_stddev",
                        "quality_interval_low",
                        "quality_interval_high",
                        "ordinal_entropy",
                        "top_decile_probability",
                        "epistemic_stddev",
                        "disagreement_range"));
        TreeMap<PolicyId, SortedMap<SourceScenario, CarryScenarioState>> grids = new TreeMap<>();
        PolicyId previousGridPolicy = null;
        SourceScenario previousGridScenario = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = width(rows.get(i), 15);
            version(row.get(0));
            PolicyId policy = PolicyId.parse(row.get(1));
            SourceScenario scenario = SourceScenario.parse(row.get(2));
            if (!required.contains(scenario) || !metadata.containsKey(policy)) {
                throw new IllegalArgumentException("Unexpected carry scenario");
            }
            if (previousGridPolicy != null
                    && (policy.compareTo(previousGridPolicy) < 0
                            || policy.equals(previousGridPolicy) && scenario.compareTo(previousGridScenario) <= 0)) {
                throw new IllegalArgumentException("Carry scenarios are not sorted");
            }
            ScenarioPrediction prediction = new ScenarioPrediction(
                    scenario,
                    finite(row.get(7)),
                    finite(row.get(8)),
                    finite(row.get(9)),
                    finite(row.get(10)),
                    finite(row.get(11)),
                    finite(row.get(12)),
                    finite(row.get(13)),
                    finite(row.get(14)));
            CarryScenarioState state = new CarryScenarioState(
                    scenario,
                    CoverageState.valueOf(row.get(3)),
                    integer(row.get(4)),
                    row.get(5).isEmpty() ? OptionalInt.empty() : OptionalInt.of(integer(row.get(5))),
                    integer(row.get(6)),
                    prediction);
            if (grids.computeIfAbsent(policy, ignored -> new TreeMap<>()).put(scenario, state) != null) {
                throw new IllegalArgumentException("Duplicate carry scenario");
            }
            previousGridPolicy = policy;
            previousGridScenario = scenario;
        }
        ArrayList<CarryForwardEntry> result = new ArrayList<>();
        for (var entry : metadata.entrySet()) {
            SortedMap<SourceScenario, CarryScenarioState> grid = grids.get(entry.getKey());
            if (grid == null || !grid.keySet().equals(required)) {
                throw new IllegalArgumentException("Incomplete carry grid");
            }
            CarrySummary summary = entry.getValue();
            CarryForwardEntry carry =
                    new CarryForwardEntry(summary.policy(), summary.firstSeen(), summary.lastUpdated(), grid);
            if (carry.validScenarioCount() != summary.valid()
                    || observedCount(carry) != summary.observed()
                    || Double.compare(pessimistic(carry), summary.pessimistic()) != 0
                    || Double.compare(maxEpistemic(carry), summary.maxEpistemic()) != 0
                    || Double.compare(maxDisagreement(carry), summary.maxDisagreement()) != 0) {
                throw new IllegalArgumentException("Carry summary does not recompute");
            }
            result.add(carry);
        }
        return List.copyOf(result);
    }

    private static List<PendingBenchmarkRun> readPending(Path file) throws IOException {
        List<List<String>> rows = CanonicalCsv.read(file);
        requireHeader(
                rows,
                List.of(
                        "schema_version",
                        "iteration",
                        "run_kind",
                        "scenario_id",
                        "benchmark_run_id",
                        "candidate_cohort_id",
                        "schedule_path",
                        "schedule_sha256",
                        "evidence_path",
                        "status"));
        ArrayList<PendingBenchmarkRun> result = new ArrayList<>();
        SourceScenario previous = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = width(rows.get(i), 10);
            version(row.get(0));
            SourceScenario scenario = SourceScenario.parse(row.get(3));
            if (previous != null && scenario.compareTo(previous) <= 0) {
                throw new IllegalArgumentException("Pending runs are not scenario sorted");
            }
            result.add(new PendingBenchmarkRun(
                    integer(row.get(1)),
                    RunKind.valueOf(row.get(2)),
                    scenario,
                    row.get(4),
                    row.get(5),
                    new ArtifactReference(row.get(6), row.get(7)),
                    row.get(8),
                    PendingRunStatus.valueOf(row.get(9))));
            previous = scenario;
        }
        return List.copyOf(result);
    }

    private static void validateInventory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Checkpoint must be a non-symlink directory");
        }
        try (var stream = Files.list(directory)) {
            if (!stream.map(path -> path.getFileName().toString())
                    .sorted()
                    .toList()
                    .equals(INVENTORY.stream().sorted().toList())) {
                throw new IllegalArgumentException("Unexpected checkpoint inventory");
            }
        }
        for (String name : INVENTORY) {
            Path file = directory.resolve(name);
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Invalid checkpoint file " + name);
            }
        }
        if (Files.size(directory.resolve("COMPLETE")) != 0) {
            throw new IllegalArgumentException("Checkpoint COMPLETE must be empty");
        }
    }

    private static void validateArtifacts(Path workspace, List<Optional<ArtifactReference>> artifacts)
            throws IOException {
        for (Optional<ArtifactReference> artifact : artifacts) {
            if (artifact.isPresent()) {
                validateArtifact(workspace, artifact.get());
            }
        }
    }

    private static void validateArtifact(Path workspace, ArtifactReference reference) throws IOException {
        Path target = workspace.resolve(reference.relativePath()).normalize();
        if (!target.startsWith(workspace) || !ArtifactFingerprint.sha256(target).equals(reference.sha256())) {
            throw new IllegalArgumentException("Artifact fingerprint mismatch");
        }
    }

    private static Optional<ArtifactReference> artifact(String path, String hash) {
        if (path.isEmpty() != hash.isEmpty()) {
            throw new IllegalArgumentException("Partial artifact reference");
        }
        return path.isEmpty() ? Optional.empty() : Optional.of(new ArtifactReference(path, hash));
    }

    private static void appendArtifact(List<String> row, Optional<ArtifactReference> reference) {
        row.add(reference.map(ArtifactReference::relativePath).orElse(""));
        row.add(reference.map(ArtifactReference::sha256).orElse(""));
    }

    private static int observedCount(CarryForwardEntry entry) {
        return Math.toIntExact(entry.scenarios().values().stream()
                .filter(state -> state.coverage() != CoverageState.MISSING)
                .count());
    }

    private static double pessimistic(CarryForwardEntry entry) {
        return entry.scenarios().values().stream()
                .filter(state -> state.coverage() != CoverageState.VALID)
                .mapToDouble(state -> state.prediction().qualityIntervalLow())
                .min()
                .orElse(1.0);
    }

    private static double maxEpistemic(CarryForwardEntry entry) {
        return entry.scenarios().values().stream()
                .filter(state -> state.coverage() != CoverageState.VALID)
                .mapToDouble(state -> state.prediction().epistemicStdDev())
                .max()
                .orElse(0.0);
    }

    private static double maxDisagreement(CarryForwardEntry entry) {
        return entry.scenarios().values().stream()
                .filter(state -> state.coverage() != CoverageState.VALID)
                .mapToDouble(state -> state.prediction().disagreementRange())
                .max()
                .orElse(0.0);
    }

    private static void writeForced(Path file, String value) throws IOException {
        Files.writeString(file, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static String fileSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static int revision(Path directory) {
        return Integer.parseInt(directory.getFileName().toString().substring("checkpoint-".length()));
    }

    private static List<Path> completeCheckpoints(Path checkpoints) throws IOException {
        try (var stream = Files.list(checkpoints)) {
            return stream.filter(path -> path.getFileName().toString().matches("checkpoint-[0-9]{8}"))
                    .filter(path -> Files.isRegularFile(path.resolve("COMPLETE"), LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList();
        }
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

    private static void requireHeader(List<List<String>> rows, List<String> header) {
        if (rows.isEmpty() || !rows.getFirst().equals(header)) {
            throw new IllegalArgumentException("Unexpected checkpoint CSV header");
        }
    }

    private static List<String> width(List<String> row, int size) {
        if (row.size() != size) {
            throw new IllegalArgumentException("Unexpected checkpoint CSV row width");
        }
        return row;
    }

    private static void version(String value) {
        if (!value.equals("1")) {
            throw new IllegalArgumentException("Unsupported checkpoint schema");
        }
    }

    private static int integer(String value) {
        return Integer.parseInt(value);
    }

    private static long hex(String value) {
        if (!value.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Invalid raw-bit field");
        }
        return Long.parseUnsignedLong(value, 16);
    }

    private static double finite(String value) {
        double result = Double.parseDouble(value);
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("Non-finite checkpoint value");
        }
        return result;
    }

    private record CarrySummary(
            PolicyVector policy,
            int firstSeen,
            int lastUpdated,
            int valid,
            int observed,
            double pessimistic,
            double maxEpistemic,
            double maxDisagreement) {}
}
