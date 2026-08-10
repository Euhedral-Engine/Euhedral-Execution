package io.euhedral_execution.training.packaging;

import io.euhedral_execution.training.DataMerger;
import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import io.euhedral_execution.training.checkpoint.CheckpointSnapshotCodec;
import io.euhedral_execution.training.checkpoint.data.ClosedLoopCheckpoint;
import io.euhedral_execution.training.checkpoint.data.EvidenceIndexEntry;
import io.euhedral_execution.training.checkpoint.data.LoadedCheckpoint;
import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.checkpoint.enums.EvidenceSource;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadata;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadataCodec;
import io.euhedral_execution.training.merge.enums.CalibrationAcceptance;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageRequest;
import io.euhedral_execution.training.packaging.enums.TrainingRunPackageStatus;
import io.euhedral_execution.training.scheduling.data.IterationSchedule;
import io.euhedral_execution.training.scheduling.io.OptimizationCorpusReader;
import io.euhedral_execution.training.scheduling.io.ScheduleCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

record PackageSourceSet(
        LoadedCheckpoint loaded,
        Path workspace,
        Path merge,
        Path model,
        Path schedule,
        IterationSchedule scheduleData,
        ScenarioModelMetadata modelMetadata,
        List<EvidenceInfo> evidence,
        TrainingRunPackageStatus status,
        CalibrationAcceptance calibrationAcceptance,
        List<String> winners,
        List<PackageOmission> omissions) {
    private static final List<String> MERGE_FILES = List.of(
            "fixed-anchors.csv",
            "reference-runs.csv",
            "calibration-report.csv",
            "scenario-results.csv",
            "robust-ranking.csv",
            "coverage-report.csv",
            "robust-leaders.vectors.csv",
            "incomplete-policies.vectors.csv");

    static PackageSourceSet resolve(TrainingRunPackageRequest request) throws IOException {
        Path workspace = request.workspace();
        LoadedCheckpoint loaded =
                CheckpointSnapshotCodec.loadRevision(workspace, request.inputs().checkpointRevision());
        ClosedLoopCheckpoint checkpoint = loaded.checkpoint();
        if (!checkpoint.trainingRunId().equals(request.inputs().trainingRunId())
                || !checkpoint.requiredScenarios().equals(request.inputs().requiredScenarios())) {
            throw new IllegalArgumentException("Package inputs disagree with checkpoint");
        }
        String expectedPackageId = packageId(checkpoint);
        if (!expectedPackageId.equals(request.inputs().packageId())) {
            throw new IllegalArgumentException("Package ID disagrees with checkpoint lifecycle");
        }
        Path merge = checkpoint
                .latestMerge()
                .map(reference -> resolveArtifact(workspace, reference.relativePath(), reference.sha256()))
                .orElse(null);
        if (merge != null) {
            validateInventory(merge, MERGE_FILES);
            OptimizationCorpusReader.read(
                    new DataMerger.MergeArtifacts(
                            merge.resolve("fixed-anchors.csv"),
                            merge.resolve("reference-runs.csv"),
                            merge.resolve("calibration-report.csv"),
                            merge.resolve("scenario-results.csv"),
                            merge.resolve("robust-ranking.csv"),
                            merge.resolve("coverage-report.csv"),
                            merge.resolve("robust-leaders.vectors.csv"),
                            merge.resolve("incomplete-policies.vectors.csv")),
                    checkpoint.requiredScenarios());
        }
        CalibrationAcceptance acceptance = merge == null ? null : calibrationAcceptance(merge);
        List<String> winners = merge == null ? List.of() : winners(merge);

        Path model = checkpoint
                .latestModel()
                .map(reference -> resolveArtifact(workspace, reference.relativePath(), reference.sha256()))
                .orElse(null);
        ScenarioModelMetadata metadata =
                model == null ? null : ScenarioModelMetadataCodec.read(model.resolve("model-metadata.json"));
        if (metadata != null && !metadata.requiredScenarios().equals(checkpoint.requiredScenarios())) {
            throw new IllegalArgumentException("Model scenario catalog mismatch");
        }

        Path schedule = selectSchedule(workspace, checkpoint);
        IterationSchedule scheduleData = schedule == null
                ? null
                : ScheduleCodec.read(
                        schedule,
                        checkpoint.requiredScenarios(),
                        checkpoint.trainingRunId(),
                        request.inputs().schedulerSeed(),
                        request.inputs().commitSha(),
                        request.inputs().dirtyWorkingTree(),
                        request.inputs().benchmarkConfig());

        ArrayList<EvidenceInfo> evidence = new ArrayList<>();
        for (EvidenceIndexEntry entry : checkpoint.evidence()) {
            Path bundle = resolveArtifact(
                    workspace, entry.bundle().relativePath(), entry.bundle().sha256());
            EvidenceCounter counter = new EvidenceCounter();
            ObservationBundleReader.stream(bundle, counter);
            if (!counter.context.descriptor().benchmarkRunId().equals(entry.benchmarkRunId())
                    || !counter.context.descriptor().scenario().equals(entry.scenario())) {
                throw new IllegalArgumentException("Evidence identity mismatch");
            }
            evidence.add(
                    new EvidenceInfo(entry, bundle, counter.context, counter.policyCount, counter.observationCount));
        }
        if (scheduleData != null && checkpoint.pendingSchedule().isEmpty()) {
            TreeSet<String> evidenceRuns = checkpoint.evidence().stream()
                    .filter(item -> item.source() == EvidenceSource.ITERATION)
                    .map(EvidenceIndexEntry::benchmarkRunId)
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (!evidenceRuns.containsAll(scheduleData.runs().stream()
                    .map(run -> run.benchmarkRunId())
                    .toList())) {
                throw new IllegalArgumentException("Final schedule lacks checkpoint evidence");
            }
        }
        TrainingRunPackageStatus status =
                switch (checkpoint.stage()) {
                    case RUN_COMPLETE -> TrainingRunPackageStatus.COMPLETE;
                    case MODEL_REJECTED -> TrainingRunPackageStatus.PARTIAL_TERMINAL;
                    default -> TrainingRunPackageStatus.PARTIAL_RECOVERABLE;
                };
        return new PackageSourceSet(
                loaded,
                workspace,
                merge,
                model,
                schedule,
                scheduleData,
                metadata,
                List.copyOf(evidence),
                status,
                acceptance,
                winners,
                omissions(checkpoint, merge, model, schedule));
    }

    static String packageId(ClosedLoopCheckpoint checkpoint) {
        return checkpoint.stage() == CheckpointStage.RUN_COMPLETE
                ? checkpoint.trainingRunId()
                : "%s.partial.r%08d".formatted(checkpoint.trainingRunId(), checkpoint.revision());
    }

    private static Path selectSchedule(Path workspace, ClosedLoopCheckpoint checkpoint) {
        if (checkpoint.pendingSchedule().isPresent()) {
            var reference = checkpoint.pendingSchedule().orElseThrow();
            return resolveArtifact(workspace, reference.relativePath(), reference.sha256());
        }
        if (checkpoint.nextIteration() > 1) {
            Path path =
                    workspace.resolve("iterations/iteration-%06d/schedule".formatted(checkpoint.nextIteration() - 1));
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Required prior schedule is absent");
            }
            return path;
        }
        return null;
    }

    private static Path resolveArtifact(Path workspace, String relative, String hash) {
        Path path = workspace.resolve(relative).normalize();
        try {
            CanonicalFileSupport.rejectSymlinkComponents(path);
            if (!path.startsWith(workspace) || !ArtifactFingerprint.sha256(path).equals(hash)) {
                throw new IllegalArgumentException("Artifact fingerprint mismatch");
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        return path;
    }

    private static void validateInventory(Path directory, List<String> expected) throws IOException {
        try (var stream = Files.list(directory)) {
            if (!stream.map(path -> path.getFileName().toString())
                    .sorted()
                    .toList()
                    .equals(expected.stream().sorted().toList())) {
                throw new IllegalArgumentException("Unexpected merge inventory");
            }
        }
    }

    private static CalibrationAcceptance calibrationAcceptance(Path merge) throws IOException {
        List<List<String>> rows = CanonicalCsv.read(merge.resolve("calibration-report.csv"));
        if (rows.size() < 2
                || rows.getFirst().size() < 2
                || !rows.getFirst().get(1).equals("calibration_acceptance")) {
            throw new IllegalArgumentException("Invalid calibration report");
        }
        CalibrationAcceptance result = CalibrationAcceptance.valueOf(rows.get(1).get(1));
        if (rows.stream().skip(1).anyMatch(row -> !row.get(1).equals(result.name()))) {
            throw new IllegalArgumentException("Mixed calibration acceptance");
        }
        return result;
    }

    private static List<String> winners(Path merge) throws IOException {
        List<List<String>> rows = CanonicalCsv.read(merge.resolve("robust-ranking.csv"));
        if (rows.isEmpty()
                || !rows.getFirst()
                        .equals(List.of(
                                "schema_version",
                                "published_rank",
                                "policy_id",
                                "eligible",
                                "required_scenario_count",
                                "observed_required_scenario_count",
                                "valid_required_scenario_count",
                                "coverage_fraction",
                                "worst_quality",
                                "quality_p25",
                                "geometric_mean_quality",
                                "cross_scenario_quality_mad",
                                "median_relative_iqr",
                                "mean_non_success_rate",
                                "mean_timeout_rate",
                                "missing_scenarios"))) {
            throw new IllegalArgumentException("Invalid robust ranking");
        }
        ArrayList<String> result = new ArrayList<>();
        for (int index = 1; index < rows.size() && result.size() < 10; index++) {
            if (rows.get(index).get(3).equals("true"))
                result.add(rows.get(index).get(2));
        }
        return List.copyOf(result);
    }

    private static List<PackageOmission> omissions(
            ClosedLoopCheckpoint checkpoint, Path merge, Path model, Path schedule) {
        ArrayList<PackageOmission> result = new ArrayList<>();
        if (merge == null) result.add(new PackageOmission("MERGE", "NOT_YET_CALIBRATED", true));
        if (model == null) result.add(new PackageOmission("MODEL", "NOT_YET_TRAINED", true));
        if (schedule == null)
            result.add(new PackageOmission(
                    "SCHEDULE",
                    checkpoint.stage() == CheckpointStage.MODEL_REJECTED
                            ? "MODEL_REJECTED_BEFORE_SCHEDULING"
                            : "NO_NORMAL_ITERATION_SCHEDULE_AT_CHECKPOINT",
                    true));
        return result.stream().sorted().toList();
    }

    record EvidenceInfo(
            EvidenceIndexEntry index,
            Path directory,
            BenchmarkRunContext context,
            int policyCount,
            long observationCount) {
        EvidenceOrigin origin() {
            return context.descriptor().evidenceOrigin();
        }
    }

    private static final class EvidenceCounter implements ObservationBundleReader.ObservationVisitor {
        private BenchmarkRunContext context;
        private int policyCount;
        private long observationCount;

        @Override
        public void onStart(BenchmarkRunContext run, List<ScheduledPolicy> policies) {
            context = run;
            policyCount = policies.size();
        }

        @Override
        public void onObservation(io.euhedral_execution.training.data.BenchmarkObservation observation) {
            observationCount = Math.addExact(observationCount, 1);
        }
    }
}
