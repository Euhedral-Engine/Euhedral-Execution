package io.euhedral_execution.training.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.AuditFixtures;
import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import io.euhedral_execution.training.checkpoint.CheckpointSnapshotCodec;
import io.euhedral_execution.training.checkpoint.data.ClosedLoopCheckpoint;
import io.euhedral_execution.training.checkpoint.data.LoadedCheckpoint;
import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.config.ClosedLoopConfig;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageRequest;
import io.euhedral_execution.training.packaging.data.TrainingRunPackage;
import io.euhedral_execution.training.packaging.enums.ArtifactSemanticType;
import io.euhedral_execution.training.packaging.enums.TrainingRunPackageStatus;
import io.euhedral_execution.training.packaging.io.TrainingRunPackageInputsCodec;
import io.euhedral_execution.training.scheduling.io.ScheduleCodec;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackageLifecycleAuditTest {
    private static final List<String> CHECKPOINT_FILES = List.of("COMPLETE",
            "carry-forward-scenarios.csv", "carry-forward.csv", "evidence-index.csv",
            "pending-runs.csv", "required-scenarios.csv", "rotation-cursors.csv",
            "state.csv");
    private static final List<String> MERGE_FILES = List.of("calibration-report.csv",
            "coverage-report.csv", "fixed-anchors.csv",
            "policy-scenario-measurements.csv", "reference-runs.csv",
            "robust-ranking.csv", "scenario-results.csv",
            "vectors/incomplete-promising.vectors.csv",
            "vectors/robust-leaders.vectors.csv");
    private static final List<String> MODEL_FILES = List.of(
            "model/ablation-evaluation.csv", "model/grouped-evaluation.csv",
            "model/loso-evaluation.csv", "model/model-metadata.json",
            "model/members/member-000/euhedral-scenario-ordinal-0000.params",
            "model/members/member-001/euhedral-scenario-ordinal-0000.params",
            "model/members/member-002/euhedral-scenario-ordinal-0000.params",
            "model/training-history.csv");
    private static final List<String> SCHEDULE_FILES = List.of("scheduler/COMPLETE",
            "scheduler/budget-report.csv", "scheduler/carry-admissions.csv",
            "scheduler/policies.csv", "scheduler/predictions.csv",
            "scheduler/runs.csv", "vectors/benchmark-ready.vectors.csv");

    @TempDir
    static Path temporary;

    private AuditFixtures.Experiment experiment;

    @BeforeAll
    void buildExperiment() throws Exception {
        experiment = AuditFixtures.execute(temporary.resolve("experiment"));
    }

    @Test
    void packagesEveryCheckpointLifecycleStageWithExactSelectedArtifacts()
            throws Exception {
        Map<CheckpointStage, Integer> firstRevision = new EnumMap<>(CheckpointStage.class);
        for (LoadedCheckpoint loaded : AuditFixtures.checkpoints(
                experiment.resumedWorkspace())) {
            firstRevision.putIfAbsent(loaded.checkpoint().stage(),
                    loaded.checkpoint().revision());
        }
        assertThat(firstRevision).containsEntry(CheckpointStage.BOOTSTRAP_PENDING, 1)
                .containsEntry(CheckpointStage.READY_TO_TRAIN, 10)
                .containsEntry(CheckpointStage.MODEL_READY, 11)
                .containsEntry(CheckpointStage.SCHEDULE_READY, 12)
                .containsEntry(CheckpointStage.BENCHMARKING, 13)
                .containsEntry(CheckpointStage.READY_TO_MERGE, 16)
                .containsEntry(CheckpointStage.RUN_COMPLETE, 24);

        List<Integer> revisions = List.of(1, 10, 11, 12, 14, 16, 17, 24);
        for (int revision : revisions) {
            LoadedCheckpoint loaded = CheckpointSnapshotCodec.loadRevision(
                    experiment.resumedWorkspace(), revision);
            TrainingRunPackage packaged = TrainingRunPackager.publish(request(
                    experiment.resumedFinalConfig(), revision,
                    temporary.resolve("lifecycle")));
            assertLifecyclePackage(packaged, loaded);
        }

        LoadedCheckpoint rejected = CheckpointSnapshotCodec.loadRevision(
                experiment.rejectedWorkspace(),
                AuditFixtures.revision(experiment.rejected().latestCheckpoint()));
        assertThat(rejected.checkpoint().stage()).isEqualTo(CheckpointStage.MODEL_REJECTED);
        assertLifecyclePackage(experiment.rejectedPackage(), rejected);
    }

    @Test
    void fixedInventoriesChecksumsManifestAndReportsMatchIndependentOracles()
            throws Exception {
        Path complete = experiment.resumedPackage().directory();
        Path interrupted = experiment.interruptedPackage().directory();
        assertThat(inventory(complete)).containsExactlyElementsOf(
                resourceLines("complete-inventory.txt"));
        assertThat(inventory(interrupted)).containsExactlyElementsOf(
                resourceLines("interrupted-inventory.txt"));

        Map<String, String> expectedDigests = resourceLines("complete-files.sha256")
                .stream().collect(Collectors.toMap(line -> line.substring(66),
                        line -> line.substring(0, 64), (left, right) -> {
                            throw new AssertionError("Duplicate golden digest");
                        }, TreeMap::new));
        assertThat(expectedDigests.keySet()).containsExactlyElementsOf(
                inventory(complete).stream()
                        .filter(path -> !path.equals("manifest.json")).toList());
        for (Map.Entry<String, String> entry : expectedDigests.entrySet()) {
            assertThat(streamSha256(complete.resolve(entry.getKey())))
                    .as(entry.getKey()).isEqualTo(entry.getValue());
        }

        TrainingRunManifest manifest = PackageManifestCodec.read(
                complete.resolve("manifest.json"));
        assertThat(manifest.files()).hasSize(69);
        assertThat(manifest.files().stream().filter(file ->
                file.semanticType() == ArtifactSemanticType.VECTOR_ONLY_DATASET)
                .map(PackageFile::path).toList())
                .allMatch(path -> path.startsWith("vectors/")
                        && path.endsWith(".vectors.csv"));
        assertThat(manifest.files().stream().filter(file -> file.semanticType()
                == ArtifactSemanticType.VECTOR_WITH_MEASUREMENTS_DATASET)
                .map(PackageFile::path)).containsExactly(
                        "policy-scenario-measurements.csv");
        assertThat(manifest.files().stream().filter(file -> file.semanticType()
                == ArtifactSemanticType.HUMAN_READABLE_REPORT)
                .map(PackageFile::path)).allMatch(path ->
                        path.startsWith("reports/") && path.endsWith(".md"));
        assertThat(PackageManifestCodec.encode(manifest))
                .isEqualTo(Files.readString(complete.resolve("manifest.json")));
        for (PackageFile entry : manifest.files()) {
            assertThat(streamSha256(complete.resolve(entry.path())))
                    .as(entry.path()).isEqualTo(entry.sha256());
            if (entry.path().endsWith("COMPLETE")) {
                assertThat(entry.sha256()).isEqualTo(AuditFixtures.EMPTY_SHA256);
            }
            if (entry.path().endsWith(".csv")) {
                assertCsvMetadata(complete.resolve(entry.path()), entry);
            }
        }

        assertOwningSourceChecksums(complete, manifest);
        assertReadmeAndReports(complete, manifest);
        assertNoPublicationPathLeak(complete);
    }

    @Test
    void publicationFailuresCollisionsAndStagingOwnershipAreTransactional()
            throws Exception {
        int revision = AuditFixtures.revision(
                experiment.resumedComplete().latestCheckpoint());
        String workspaceHash = ArtifactFingerprint.sha256(
                experiment.resumedWorkspace());
        for (TrainingRunPackager.PublicationPoint point
                : TrainingRunPackager.PublicationPoint.values()) {
            Path output = temporary.resolve("publication-failure/" + point);
            TrainingRunPackageRequest request = request(
                    experiment.resumedFinalConfig(), revision, output);
            assertThatThrownBy(() -> TrainingRunPackager.publish(request, reached -> {
                if (reached == point) {
                    throw new IOException("injected " + point);
                }
            })).isInstanceOf(IOException.class)
                    .hasMessageContaining("injected " + point);
            assertThat(output.resolve("training-run-" + AuditFixtures.TRAINING_RUN_ID))
                    .doesNotExist();
            assertThat(stagingEntries(output)).isEmpty();
            assertThat(ArtifactFingerprint.sha256(experiment.resumedWorkspace()))
                    .isEqualTo(workspaceHash);
        }

        Path collisionRoot = temporary.resolve("collisions");
        TrainingRunPackageRequest original = request(
                experiment.resumedFinalConfig(), revision, collisionRoot);
        TrainingRunPackage first = TrainingRunPackager.publish(original);
        TrainingRunPackage identical = TrainingRunPackager.publish(original);
        assertThat(identical.directory()).isEqualTo(first.directory());
        assertThat(ArtifactFingerprint.sha256(identical.directory()))
                .isEqualTo(ArtifactFingerprint.sha256(first.directory()));
        String targetHash = ArtifactFingerprint.sha256(first.directory());

        TrainingRunPackageInputs changed = new TrainingRunPackageInputs(
                original.inputs().packageId(), original.inputs().trainingRunId(),
                original.inputs().checkpointRevision(),
                original.inputs().schedulerSeed() + 1,
                original.inputs().commitSha(), original.inputs().dirtyWorkingTree(),
                original.inputs().benchmarkConfig(), original.inputs().requiredScenarios());
        assertThatThrownBy(() -> TrainingRunPackager.publish(
                new TrainingRunPackageRequest(original.workspace(), collisionRoot, changed)))
                .isInstanceOf(PackageCollisionException.class);
        assertThat(ArtifactFingerprint.sha256(first.directory())).isEqualTo(targetHash);

        TrainingRunPackageInputs changedRevision = new TrainingRunPackageInputs(
                original.inputs().packageId(), original.inputs().trainingRunId(),
                revision - 1, original.inputs().schedulerSeed(),
                original.inputs().commitSha(), original.inputs().dirtyWorkingTree(),
                original.inputs().benchmarkConfig(), original.inputs().requiredScenarios());
        assertThatThrownBy(() -> TrainingRunPackager.publish(
                new TrainingRunPackageRequest(original.workspace(), collisionRoot,
                        changedRevision))).isInstanceOf(PackageCollisionException.class);
        assertThat(ArtifactFingerprint.sha256(first.directory())).isEqualTo(targetHash);

        Path changedWorkspace = temporary.resolve("changed-checkpoint-workspace");
        copyTree(experiment.resumedWorkspace(), changedWorkspace);
        Files.writeString(changedWorkspace.resolve(
                "checkpoints/checkpoint-%08d/unexpected".formatted(revision)), "changed\n");
        assertThatThrownBy(() -> TrainingRunPackager.publish(
                new TrainingRunPackageRequest(changedWorkspace, collisionRoot,
                        original.inputs()))).isInstanceOf(PackageCollisionException.class);
        assertThat(ArtifactFingerprint.sha256(first.directory())).isEqualTo(targetHash);

        Path manifestRoot = temporary.resolve("manifest-collision");
        TrainingRunPackage manifestPackage = TrainingRunPackager.publish(request(
                experiment.resumedFinalConfig(), revision, manifestRoot));
        Files.writeString(manifestPackage.manifest(), " ", StandardOpenOption.APPEND);
        String corruptHash = ArtifactFingerprint.sha256(manifestPackage.directory());
        assertThatThrownBy(() -> TrainingRunPackager.publish(request(
                experiment.resumedFinalConfig(), revision, manifestRoot)))
                .isInstanceOf(PackageCollisionException.class);
        assertThat(ArtifactFingerprint.sha256(manifestPackage.directory()))
                .isEqualTo(corruptHash);

        Path ownedRoot = temporary.resolve("owned-staging");
        Files.createDirectories(ownedRoot);
        Path owned = ownedRoot.resolve(".training-run-phase6-audit.tmp-owned");
        Files.createDirectory(owned);
        Files.writeString(owned.resolve(".euhedral-package-staging"),
                AuditFixtures.TRAINING_RUN_ID + "\n");
        Path unrelated = ownedRoot.resolve(".training-run-phase6-audit.tmpx-preserve");
        Files.createDirectory(unrelated);
        TrainingRunPackager.publish(request(experiment.resumedFinalConfig(),
                revision, ownedRoot));
        assertThat(owned).doesNotExist();
        assertThat(unrelated).isDirectory();

        Path unownedRoot = temporary.resolve("unowned-staging");
        Files.createDirectories(unownedRoot);
        Path unowned = unownedRoot.resolve(".training-run-phase6-audit.tmp-foreign");
        Files.createDirectory(unowned);
        Files.writeString(unowned.resolve("owner.txt"), "foreign\n");
        assertThatThrownBy(() -> TrainingRunPackager.publish(request(
                experiment.resumedFinalConfig(), revision, unownedRoot)))
                .isInstanceOf(PackageCollisionException.class);
        assertThat(Files.readString(unowned.resolve("owner.txt"))).isEqualTo("foreign\n");
    }

    @Test
    void validatorRejectsEveryArtifactFamilyAndMalformedManifestSurface()
            throws Exception {
        Path source = experiment.resumedPackage().directory();
        TrainingRunManifest manifest = PackageManifestCodec.read(
                source.resolve("manifest.json"));
        Map<ArtifactSemanticType, String> representatives = new EnumMap<>(
                ArtifactSemanticType.class);
        manifest.files().forEach(file ->
                representatives.putIfAbsent(file.semanticType(), file.path()));
        assertThat(representatives.keySet())
                .contains(ArtifactSemanticType.MODEL_MEMBER_PARAMETERS,
                        ArtifactSemanticType.RAW_OBSERVATIONS,
                        ArtifactSemanticType.CHECKPOINT_STATE,
                        ArtifactSemanticType.SCHEDULE_DATASET,
                        ArtifactSemanticType.MERGE_DATASET,
                        ArtifactSemanticType.HUMAN_READABLE_REPORT);
        for (Map.Entry<ArtifactSemanticType, String> entry
                : representatives.entrySet()) {
            Path copy = copyPackage(source, "tamper-" + entry.getKey());
            Files.write(copy.resolve(entry.getValue()), new byte[]{1},
                    StandardOpenOption.APPEND);
            String before = ArtifactFingerprint.sha256(copy);
            assertThatThrownBy(() -> TrainingRunPackageValidator.validate(copy))
                    .as(entry.getKey().name()).isInstanceOf(IOException.class);
            assertThat(ArtifactFingerprint.sha256(copy)).isEqualTo(before);
        }

        Path unexpected = copyPackage(source, "unexpected");
        Files.writeString(unexpected.resolve("unexpected.txt"), "unexpected\n");
        String unexpectedHash = ArtifactFingerprint.sha256(unexpected);
        assertThatThrownBy(() -> TrainingRunPackageValidator.validate(unexpected))
                .isInstanceOf(IOException.class);
        assertThat(ArtifactFingerprint.sha256(unexpected)).isEqualTo(unexpectedHash);

        Path symlink = copyPackage(source, "symlink");
        Files.createSymbolicLink(symlink.resolve("unsafe-link"),
                Path.of("README.md"));
        assertThatThrownBy(() -> TrainingRunPackageValidator.validate(symlink))
                .isInstanceOf(IOException.class);
        assertThat(Files.readSymbolicLink(symlink.resolve("unsafe-link")))
                .isEqualTo(Path.of("README.md"));

        Path scheduleMismatch = copyPackage(source, "schedule-evidence-mismatch");
        ClosedLoopCheckpoint checkpoint = CheckpointSnapshotCodec.readDetachedForAudit(
                scheduleMismatch.resolve("checkpoints/latest"));
        TrainingRunPackageInputs inputs = TrainingRunPackageInputsCodec.read(
                scheduleMismatch.resolve("provenance/package-inputs.properties"));
        List<List<String>> scheduleRuns = CanonicalCsv.read(
                scheduleMismatch.resolve("scheduler/runs.csv"));
        Path rawRun = scheduleMismatch.resolve("raw-data/bundles")
                .resolve(scheduleRuns.get(1).get(
                        scheduleRuns.getFirst().indexOf("benchmark_run_id")))
                .resolve("run.csv");
        List<List<String>> rawRows = CanonicalCsv.read(rawRun);
        ArrayList<String> changedRaw = new ArrayList<>(rawRows.get(1));
        changedRaw.set(3, "cohort-mismatch");
        Files.writeString(rawRun, CanonicalCsv.row(rawRows.getFirst())
                + CanonicalCsv.row(changedRaw));
        assertThatThrownBy(() -> TrainingRunPackageValidator.validateSchedule(
                scheduleMismatch, checkpoint, inputs))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Schedule/raw evidence mismatch");

        String canonical = Files.readString(source.resolve("manifest.json"));
        Map<String, String> variants = new LinkedHashMap<>();
        variants.put("duplicate-key", canonical.replaceFirst(
                "\\{\\n", "{\\n  \"artifact_type\": \"duplicate\",\\n"));
        variants.put("unknown-key", canonical.replaceFirst(
                "\\{\\n", "{\\n  \"unexpected\": true,\\n"));
        variants.put("missing-key", canonical.replace(
                "  \"artifact_type\": \"euhedral-training-run-package\",\n", ""));
        variants.put("out-of-order-key", canonical.replace(
                "  \"artifact_type\": \"euhedral-training-run-package\",\n"
                        + "  \"schema_version\": 1,\n",
                "  \"schema_version\": 1,\n"
                        + "  \"artifact_type\": \"euhedral-training-run-package\",\n"));
        variants.put("path", canonical.replaceFirst(
                "\"path\": \"README.md\"", "\"path\": \"../README.md\""));
        variants.put("backslash-path", canonical.replaceFirst(
                "\"path\": \"README.md\"", "\"path\": \"bad\\\\path\""));
        variants.put("duplicate-path", canonical.replaceFirst(
                "\"path\": \"calibration-report.csv\"", "\"path\": \"README.md\""));
        variants.put("hash", canonical.replaceFirst(
                "\"sha256\": \"[0-9a-f]{64}\"",
                "\"sha256\": \"" + "0".repeat(64) + "\""));
        variants.put("uppercase-hash", canonical.replaceFirst(
                "\"sha256\": \"[0-9a-f]{64}\"",
                "\"sha256\": \"" + "A".repeat(64) + "\""));
        variants.put("duplicate-run-id", canonical.replaceFirst(
                "\"source_run_ids\": \\[\"([^\"]+)\", \"([^\"]+)\"",
                "\"source_run_ids\": [\"$1\", \"$1\""));
        variants.put("unsorted-run-id", canonical.replaceFirst(
                "\"source_run_ids\": \\[\"([^\"]+)\", \"([^\"]+)\"",
                "\"source_run_ids\": [\"$2\", \"$1\""));
        variants.put("schema", canonical.replaceFirst(
                "\"schema_version\": 1", "\"schema_version\": 2"));
        variants.put("count", canonical.replaceFirst(
                "\"row_count\": [0-9]+", "\"row_count\": 999999"));
        variants.put("status", canonical.replaceFirst(
                "\"status\": \"COMPLETE\"",
                "\"status\": \"PARTIAL_RECOVERABLE\""));
        variants.put("origin", canonical.replaceFirst(
                "\"origin\": \"UPGRADED_RUN\"", "\"origin\": \"MIXED\""));
        variants.put("omission", canonical.replace(
                "  \"omissions\": [  ]\n",
                "  \"omissions\": [{\"semantic_group\":\"MODEL\","
                        + "\"reason\":\"NOT_YET_TRAINED\","
                        + "\"required_for_complete_run\":true}]\n"));
        for (Map.Entry<String, String> variant : variants.entrySet()) {
            assertThat(variant.getValue()).as(variant.getKey())
                    .isNotEqualTo(canonical);
            Path copy = copyPackage(source, "manifest-" + variant.getKey());
            Files.writeString(copy.resolve("manifest.json"), variant.getValue());
            String before = ArtifactFingerprint.sha256(copy);
            assertThatThrownBy(() -> TrainingRunPackageValidator.validate(copy))
                    .as(variant.getKey()).isInstanceOfAny(
                            IOException.class, IllegalArgumentException.class);
            assertThat(ArtifactFingerprint.sha256(copy)).isEqualTo(before);
        }
    }

    private void assertLifecyclePackage(TrainingRunPackage packaged,
            LoadedCheckpoint loaded) throws Exception {
        ClosedLoopCheckpoint checkpoint = loaded.checkpoint();
        TrainingRunManifest manifest = PackageManifestCodec.read(packaged.manifest());
        boolean merge = checkpoint.latestMerge().isPresent();
        boolean model = checkpoint.latestModel().isPresent();
        boolean schedule = checkpoint.pendingSchedule().isPresent()
                || checkpoint.nextIteration() > 1;
        TrainingRunPackageStatus expectedStatus = switch (checkpoint.stage()) {
            case RUN_COMPLETE -> TrainingRunPackageStatus.COMPLETE;
            case MODEL_REJECTED -> TrainingRunPackageStatus.PARTIAL_TERMINAL;
            default -> TrainingRunPackageStatus.PARTIAL_RECOVERABLE;
        };
        String expectedId = checkpoint.stage() == CheckpointStage.RUN_COMPLETE
                ? checkpoint.trainingRunId()
                : "%s.partial.r%08d".formatted(checkpoint.trainingRunId(),
                checkpoint.revision());
        assertThat(packaged.packageId()).isEqualTo(expectedId);
        assertThat(packaged.status()).isEqualTo(expectedStatus);
        assertThat(manifest.checkpointRevision()).isEqualTo(checkpoint.revision());
        assertThat(manifest.runComplete())
                .isEqualTo(checkpoint.stage() == CheckpointStage.RUN_COMPLETE);
        assertThat(manifest.omissions()).containsExactlyElementsOf(
                expectedOmissions(checkpoint, merge, model, schedule));
        assertThat(inventory(packaged.directory())).containsExactlyElementsOf(
                expectedInventory(checkpoint, merge, model, schedule));
        assertThat(manifest.files().stream()
                .filter(file -> file.path().startsWith("raw-data/bundles/")
                        && file.path().endsWith("/run.csv")).count())
                .isEqualTo(checkpoint.evidence().size());
        if (schedule) {
            Path expected = checkpoint.pendingSchedule()
                    .map(reference -> loaded.snapshotDirectory().getParent().getParent()
                            .resolve(reference.relativePath()))
                    .orElseGet(() -> loaded.snapshotDirectory().getParent().getParent()
                            .resolve("iterations/iteration-%06d/schedule"
                                    .formatted(checkpoint.nextIteration() - 1)));
            assertThat(ArtifactFingerprint.sha256(packaged.directory().resolve("scheduler")))
                    .isEqualTo(ArtifactFingerprint.sha256(expected));
            assertThat(ScheduleCodec.read(packaged.directory().resolve("scheduler"),
                    checkpoint.requiredScenarios(), checkpoint.trainingRunId(),
                    experiment.resumedFinalConfig().schedulerSeed(),
                    experiment.resumedFinalConfig().commitSha(),
                    experiment.resumedFinalConfig().dirtyWorkingTree(),
                    experiment.resumedFinalConfig().benchmarkConfig()).iteration())
                    .isEqualTo(checkpoint.nextIteration()
                            - (checkpoint.pendingSchedule().isEmpty() ? 1 : 0));
        }
    }

    private static List<PackageOmission> expectedOmissions(
            ClosedLoopCheckpoint checkpoint, boolean merge, boolean model,
            boolean schedule) {
        ArrayList<PackageOmission> result = new ArrayList<>();
        if (!merge) {
            result.add(new PackageOmission("MERGE", "NOT_YET_CALIBRATED", true));
        }
        if (!model) {
            result.add(new PackageOmission("MODEL", "NOT_YET_TRAINED", true));
        }
        if (!schedule) {
            result.add(new PackageOmission("SCHEDULE",
                    checkpoint.stage() == CheckpointStage.MODEL_REJECTED
                            ? "MODEL_REJECTED_BEFORE_SCHEDULING"
                            : "NO_NORMAL_ITERATION_SCHEDULE_AT_CHECKPOINT", true));
        }
        return result.stream().sorted().toList();
    }

    private static List<String> expectedInventory(ClosedLoopCheckpoint checkpoint,
            boolean merge, boolean model, boolean schedule) {
        ArrayList<String> result = new ArrayList<>(List.of("README.md", "manifest.json",
                "provenance/package-inputs.properties", "raw-data/index.csv",
                "reports/robust-ranking.md",
                "reports/source-scenario-comparison.md"));
        CHECKPOINT_FILES.forEach(name -> result.add("checkpoints/latest/" + name));
        checkpoint.evidence().forEach(evidence -> {
            String prefix = "raw-data/bundles/" + evidence.benchmarkRunId() + "/";
            for (String name : List.of("COMPLETE", "observations.csv",
                    "policies.csv", "run.csv")) {
                result.add(prefix + name);
            }
        });
        if (merge) {
            result.addAll(MERGE_FILES);
        }
        if (model) {
            result.addAll(MODEL_FILES);
        }
        if (schedule) {
            result.addAll(SCHEDULE_FILES);
        }
        return result.stream().sorted().toList();
    }

    private void assertOwningSourceChecksums(Path complete,
            TrainingRunManifest manifest) throws Exception {
        assertThat(ArtifactFingerprint.sha256(complete.resolve("checkpoints/latest")))
                .isEqualTo(manifest.checkpointSha256());
        for (var evidence : CheckpointSnapshotCodec.loadLatest(
                experiment.resumedWorkspace(), AuditFixtures.TRAINING_RUN_ID,
                experiment.configSha256()).orElseThrow().checkpoint().evidence()) {
            Path packaged = complete.resolve("raw-data/bundles")
                    .resolve(evidence.benchmarkRunId());
            Path source = experiment.resumedWorkspace()
                    .resolve(evidence.bundle().relativePath());
            assertThat(ArtifactFingerprint.sha256(packaged))
                    .isEqualTo(ArtifactFingerprint.sha256(source));
        }
        assertThat(ArtifactFingerprint.sha256(complete.resolve("model")))
                .isEqualTo(ArtifactFingerprint.sha256(
                        experiment.resumedWorkspace().resolve("models/model-000002")));
        assertThat(ArtifactFingerprint.sha256(complete.resolve("scheduler")))
                .isEqualTo(ArtifactFingerprint.sha256(experiment.resumedWorkspace()
                        .resolve("iterations/iteration-000002/schedule")));
        for (String name : List.of("fixed-anchors.csv", "reference-runs.csv",
                "calibration-report.csv", "scenario-results.csv", "robust-ranking.csv",
                "coverage-report.csv")) {
            assertThat(Files.mismatch(complete.resolve(name),
                    experiment.resumedWorkspace().resolve(
                            "merges/merge-000002").resolve(name))).isEqualTo(-1);
        }
    }

    private void assertReadmeAndReports(Path complete,
            TrainingRunManifest manifest) throws Exception {
        String readme = Files.readString(complete.resolve("README.md"));
        int previous = -1;
        for (String heading : List.of("# Euhedral training run phase6-audit",
                "## Status", "## Winning policies", "## Required source scenarios",
                "## Coverage and ranking rule", "## Calibration health", "## Model",
                "## Package guide", "## Provenance", "## Reproduce this package")) {
            int current = readme.indexOf(heading);
            assertThat(current).as(heading).isGreaterThan(previous);
            previous = current;
        }
        assertThat(readme).contains("- Checkpoint stage: `RUN_COMPLETE`",
                "- Checkpoint revision: 24", "- Package status: `COMPLETE`",
                "- Omissions: none", "- Reference runs: 4",
                "- Strong calibrated runs: 4", "- Weak calibrated runs: 0",
                "- Failed/uncalibrated runs: 0",
                "Model status: `accepted/deployable`",
                "`vectors/*.vectors.csv`: vector-only datasets",
                "`policy-scenario-measurements.csv`: vectors with measurements",
                "machine-readable datasets", "human-readable reports",
                "- Producer commit: `" + AuditFixtures.COMMIT_SHA + "`",
                "- Dirty working tree: false", "- Evidence: native=8, imported=0, mixed=0",
                "\"$EUHEDRAL_TRAINER\" package-run --workspace ../..");
        for (var scenario : experiment.corpus().scenarios()) {
            assertThat(readme).contains("| " + scenario.canonical() + " | "
                    + scenario.environmentId() + " | " + scenario.sourceCount()
                    + " | " + scenario.availablePhysicalCoreCount() + " | "
                    + scenario.ratio().numerator() + "/"
                    + scenario.ratio().denominator() + " |");
        }

        String rankingReport = Files.readString(
                complete.resolve("reports/robust-ranking.md"));
        List<List<String>> leaderVectors = CanonicalCsv.read(
                complete.resolve("vectors/robust-leaders.vectors.csv"));
        int firstWeight = leaderVectors.getFirst().indexOf("weight_00_bits");
        String decimalPrefix = "[" + Double.longBitsToDouble(Long.parseUnsignedLong(
                leaderVectors.get(1).get(firstWeight), 16)) + ", "
                + Double.longBitsToDouble(Long.parseUnsignedLong(
                leaderVectors.get(1).get(firstWeight + 1), 16));
        assertThat(readme).contains("### Winning policy vectors", decimalPrefix);
        assertThat(rankingReport).contains("### Winning policy vectors", decimalPrefix);
        List<List<String>> ranking = CanonicalCsv.read(
                complete.resolve("robust-ranking.csv"));
        assertThat(rankingReport.indexOf(manifest.winningPolicyIds().getFirst()))
                .isLessThan(rankingReport.indexOf(manifest.winningPolicyIds().get(1)));
        for (List<String> row : ranking.subList(1, ranking.size())) {
            if (!row.get(1).isEmpty()) {
                assertThat(rankingReport).contains("| " + row.get(1) + " | "
                        + row.get(2) + " | " + row.get(8) + " | " + row.get(9)
                        + " | " + row.get(10) + " | " + row.get(11) + " |");
            }
        }
        assertThat(rankingReport).contains(experiment.failedPolicyId().canonical());

        String scenarioReport = Files.readString(
                complete.resolve("reports/source-scenario-comparison.md"));
        int last = -1;
        for (var scenario : experiment.corpus().scenarios()) {
            int current = scenarioReport.indexOf("## " + scenario.canonical());
            assertThat(current).isGreaterThan(last);
            last = current;
        }
    }

    private void assertNoPublicationPathLeak(Path complete) throws Exception {
        List<String> forbidden = List.of(experiment.root().toString(),
                experiment.resumedWorkspace().toString(),
                experiment.resumedPackages().toString(), ".tmp-");
        for (String relative : inventory(complete)) {
            Path file = complete.resolve(relative);
            if (relative.equals("manifest.json") || Files.size(file) == 0) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            for (String value : forbidden) {
                assertThat(text).as(relative).doesNotContain(value);
            }
        }
    }

    private static void assertCsvMetadata(Path file, PackageFile entry)
            throws Exception {
        long rows = 0;
        String header;
        try (BufferedReader reader = Files.newBufferedReader(file,
                StandardCharsets.UTF_8)) {
            header = reader.readLine();
            while (reader.readLine() != null) {
                rows++;
            }
        }
        assertThat(rows).isEqualTo(entry.rowCount());
        assertThat(header).startsWith("schema_version,");
        try (BufferedReader reader = Files.newBufferedReader(file,
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("schema_version,")) {
                    assertThat(line).startsWith(entry.schemaVersion().toString() + ",");
                }
            }
        }
    }

    private TrainingRunPackageRequest request(ClosedLoopConfig config, int revision,
            Path outputRoot) throws Exception {
        ClosedLoopCheckpoint checkpoint = CheckpointSnapshotCodec.loadRevision(
                config.workspace(), revision).checkpoint();
        String packageId = checkpoint.stage() == CheckpointStage.RUN_COMPLETE
                ? checkpoint.trainingRunId()
                : "%s.partial.r%08d".formatted(checkpoint.trainingRunId(), revision);
        return new TrainingRunPackageRequest(config.workspace(), outputRoot,
                new TrainingRunPackageInputs(packageId, config.trainingRunId(), revision,
                        config.schedulerSeed(), config.commitSha(),
                        config.dirtyWorkingTree(), config.benchmarkConfig(),
                        config.requiredScenarios()));
    }

    private Path copyPackage(Path source, String name) throws Exception {
        Path target = temporary.resolve("mutations").resolve(name);
        copyTree(source, target);
        return target;
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static List<String> inventory(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            return stream.filter(path -> Files.isRegularFile(path,
                            LinkOption.NOFOLLOW_LINKS))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    private List<String> resourceLines(String name) throws Exception {
        String resource = "/robust-training/v1/golden-package/" + name;
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("Missing golden resource " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII)
                    .lines().filter(line -> !line.isEmpty()).toList();
        }
    }

    private static String streamSha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<Path> stagingEntries(Path output) throws Exception {
        if (!Files.isDirectory(output)) {
            return List.of();
        }
        try (var stream = Files.list(output)) {
            return stream.filter(path -> path.getFileName().toString()
                    .contains(".tmp-")).toList();
        }
    }
}
