package io.euhedral_execution.training.packaging;

import io.euhedral_execution.training.checkpoint.CheckpointSnapshotCodec;
import io.euhedral_execution.training.checkpoint.data.ArtifactReference;
import io.euhedral_execution.training.checkpoint.data.ClosedLoopCheckpoint;
import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.checkpoint.enums.EvidenceSource;
import io.euhedral_execution.training.data.BenchmarkObservation;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.learning.metadata.MemberMetadata;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadata;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadataCodec;
import io.euhedral_execution.training.merge.enums.CalibrationAcceptance;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.data.TrainingRunPackage;
import io.euhedral_execution.training.packaging.enums.ArtifactOrigin;
import io.euhedral_execution.training.packaging.enums.ArtifactSemanticType;
import io.euhedral_execution.training.packaging.enums.TrainingRunPackageStatus;
import io.euhedral_execution.training.packaging.io.TrainingRunPackageInputsCodec;
import io.euhedral_execution.training.scheduling.io.ScheduleCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrainingRunPackageValidator {
    public static TrainingRunPackage validate(Path packageDirectory) throws IOException {
        Path root = packageDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Package root must be a non-symlink directory");
        }
        rejectUnsupportedEntries(root);
        Path manifestPath = root.resolve("manifest.json");
        TrainingRunManifest manifest = PackageManifestCodec.read(manifestPath);
        Map<String, PackageFile> declared = new LinkedHashMap<>();
        for (PackageFile entry : manifest.files()) {
            if (declared.put(entry.path(), entry) != null) {
                throw new IOException("Duplicate manifest path");
            }
        }
        List<String> actual;
        try (var stream = Files.walk(root)) {
            actual = stream.filter(path -> Files.isRegularFile(path,
                            LinkOption.NOFOLLOW_LINKS))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted().toList();
        }
        ArrayList<String> expected = new ArrayList<>(declared.keySet());
        expected.add("manifest.json");
        expected.sort(String::compareTo);
        if (!actual.equals(expected)) throw new IOException("Package inventory mismatch");

        for (PackageFile entry : manifest.files()) {
            var classification = TrainingRunPackager.classify(entry.path());
            if (entry.semanticType() != classification.semanticType()
                    || entry.producingStage() != classification.producingStage()
                    || !entry.mediaType().equals(TrainingRunPackager.mediaType(entry.path()))
                    || !entry.complete()) {
                throw new IOException("Manifest file semantics mismatch: " + entry.path());
            }
            Path file = root.resolve(entry.path()).normalize();
            if (!file.startsWith(root)
                    || !CanonicalFileSupport.sha256(file).equals(entry.sha256())) {
                throw new IOException("Package checksum mismatch: " + entry.path());
            }
            if (entry.path().endsWith(".csv")) {
                var csv = CanonicalFileSupport.csvMetadata(file);
                if (!java.util.Objects.equals(csv.schemaVersion(), entry.schemaVersion())
                        || csv.rowCount() != entry.rowCount()) {
                    throw new IOException("Package CSV metadata mismatch: " + entry.path());
                }
            } else if (entry.schemaVersion() != null || entry.rowCount() != null) {
                throw new IOException("Non-CSV file has CSV metadata");
            }
            if (entry.semanticType() == ArtifactSemanticType.COMPLETION_MARKER
                    && Files.size(file) != 0) {
                throw new IOException("Completion marker must be empty");
            }
        }

        TrainingRunPackageInputs inputs = TrainingRunPackageInputsCodec.read(
                root.resolve("provenance/package-inputs.properties"));
        ClosedLoopCheckpoint checkpoint = CheckpointSnapshotCodec.readDetachedForAudit(
                root.resolve("checkpoints/latest"));
        if (!manifest.packageId().equals(inputs.packageId())
                || !manifest.trainingRunId().equals(inputs.trainingRunId())
                || manifest.checkpointRevision() != inputs.checkpointRevision()
                || !checkpoint.trainingRunId().equals(manifest.trainingRunId())
                || checkpoint.revision() != manifest.checkpointRevision()
                || checkpoint.stage() != manifest.checkpointStage()
                || !checkpoint.configSha256().equals(manifest.configSha256())
                || !checkpoint.requiredScenarios().equals(inputs.requiredScenarios())
                || !CanonicalFileSupport.sha256(root.resolve("checkpoints/latest"))
                .equals(manifest.checkpointSha256())) {
            throw new IOException("Package/checkpoint identity mismatch");
        }
        validateStatus(manifest, checkpoint);
        validateLifecycleArtifacts(root, manifest, checkpoint);
        validateRawData(root, manifest, checkpoint);
        validateReferences(root, checkpoint);
        validateMerge(root, checkpoint);
        validateSchedule(root, checkpoint, inputs);
        validateModel(root, checkpoint);
        validateOrigins(root, manifest);
        return new TrainingRunPackage(root, manifestPath, manifest.packageId(),
                manifest.status());
    }

    private static void rejectUnsupportedEntries(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Package contains symlink");
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Package contains unsupported file type");
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!relative.isEmpty()) CanonicalFileSupport.validateRelative(relative);
                if (path.getFileName().toString().equals(".euhedral-package-staging")) {
                    throw new IOException("Package contains staging marker");
                }
            }
        }
    }

    private static void validateStatus(TrainingRunManifest manifest,
            ClosedLoopCheckpoint checkpoint) throws IOException {
        TrainingRunPackageStatus expected = switch (checkpoint.stage()) {
            case RUN_COMPLETE -> TrainingRunPackageStatus.COMPLETE;
            case MODEL_REJECTED -> TrainingRunPackageStatus.PARTIAL_TERMINAL;
            default -> TrainingRunPackageStatus.PARTIAL_RECOVERABLE;
        };
        if (manifest.status() != expected
                || !manifest.packageId().equals(PackageSourceSet.packageId(checkpoint))) {
            throw new IOException("Package lifecycle mismatch");
        }
    }

    private static void validateLifecycleArtifacts(Path root, TrainingRunManifest manifest,
            ClosedLoopCheckpoint checkpoint) throws IOException {
        boolean merge = Files.isRegularFile(root.resolve("robust-ranking.csv"),
                LinkOption.NOFOLLOW_LINKS);
        boolean model = Files.isDirectory(root.resolve("model"), LinkOption.NOFOLLOW_LINKS);
        boolean schedule = Files.isDirectory(root.resolve("scheduler"),
                LinkOption.NOFOLLOW_LINKS);
        if (merge != checkpoint.latestMerge().isPresent()
                || model != checkpoint.latestModel().isPresent()
                || requiresSchedule(checkpoint) != schedule) {
            throw new IOException("Package lifecycle artifact presence mismatch");
        }
        ArrayList<PackageOmission> omissions = new ArrayList<>();
        if (!merge) omissions.add(new PackageOmission("MERGE", "NOT_YET_CALIBRATED", true));
        if (!model) omissions.add(new PackageOmission("MODEL", "NOT_YET_TRAINED", true));
        if (!schedule) omissions.add(new PackageOmission("SCHEDULE",
                checkpoint.stage() == CheckpointStage.MODEL_REJECTED
                        ? "MODEL_REJECTED_BEFORE_SCHEDULING"
                        : "NO_NORMAL_ITERATION_SCHEDULE_AT_CHECKPOINT", true));
        if (!manifest.omissions().equals(omissions.stream().sorted().toList())) {
            throw new IOException("Manifest omissions do not match lifecycle");
        }
        if (merge) {
            List<List<String>> ranking = CanonicalCsv.read(
                    root.resolve("robust-ranking.csv"));
            ArrayList<String> winners = new ArrayList<>();
            for (int index = 1; index < ranking.size() && winners.size() < 10; index++) {
                if (ranking.get(index).get(3).equals("true")) {
                    winners.add(ranking.get(index).get(2));
                }
            }
            if (!manifest.winningPolicyIds().equals(winners)) {
                throw new IOException("Manifest winners disagree with robust ranking");
            }
            List<List<String>> calibration = CanonicalCsv.read(
                    root.resolve("calibration-report.csv"));
            CalibrationAcceptance acceptance = CalibrationAcceptance.valueOf(
                    calibration.get(1).get(1));
            if (manifest.calibrationAcceptance() != acceptance) {
                throw new IOException("Manifest calibration mode mismatch");
            }
        } else if (manifest.calibrationAcceptance() != null
                || !manifest.winningPolicyIds().isEmpty()) {
            throw new IOException("Unavailable merge has manifest-derived values");
        }
        String readme = Files.readString(root.resolve("README.md"), StandardCharsets.UTF_8);
        for (String heading : List.of("# Euhedral training run ", "## Status",
                "## Winning policies", "## Required source scenarios",
                "## Coverage and ranking rule", "## Calibration health", "## Model",
                "## Package guide", "## Provenance", "## Reproduce this package")) {
            if (!readme.contains(heading)) throw new IOException("README section is absent");
        }
        if (!readme.contains("\"$EUHEDRAL_TRAINER\" package-run --workspace ../.. "
                + "--inputs provenance/package-inputs.properties --output-root "
                + "\"$OUTPUT_ROOT\"")) {
            throw new IOException("README reproduction command mismatch");
        }
    }

    private static void validateRawData(Path root, TrainingRunManifest manifest,
            ClosedLoopCheckpoint checkpoint) throws IOException {
        List<List<String>> indexRows = CanonicalCsv.read(
                root.resolve("raw-data/index.csv"));
        if (indexRows.size() != checkpoint.evidence().size() + 1) {
            throw new IOException("Raw index/checkpoint evidence count mismatch");
        }
        for (int index = 1; index < indexRows.size(); index++) {
            List<String> row = indexRows.get(index);
            if (row.size() != 13 || !row.get(12).equals("true")) {
                throw new IOException("Invalid raw index");
            }
            var expected = checkpoint.evidence().get(index - 1);
            if (!row.get(1).equals(expected.benchmarkRunId())
                    || !row.get(3).equals(expected.scenario().canonical())
                    || !row.get(4).equals(expected.source().name())
                    || !row.get(7).equals(expected.bundle().sha256())) {
                throw new IOException("Raw index/checkpoint mismatch");
            }
            Path bundle = root.resolve(row.get(6));
            Counter counter = new Counter();
            ObservationBundleReader.stream(bundle, counter);
            if (!counter.context.descriptor().benchmarkRunId().equals(row.get(1))
                    || !counter.context.descriptor().scenario().canonical().equals(row.get(3))
                    || !counter.context.descriptor().evidenceOrigin().name().equals(row.get(5))
                    || !counter.context.descriptor().startedAt().toString().equals(row.get(8))
                    || !counter.context.completedAt().toString().equals(row.get(9))
                    || counter.policyCount != Integer.parseInt(row.get(10))
                    || counter.observationCount != Long.parseLong(row.get(11))
                    || !virtualFingerprint(Map.of("run.csv", bundle.resolve("run.csv"),
                    "policies.csv", bundle.resolve("policies.csv"),
                    "observations.csv", bundle.resolve("observations.csv"),
                    "COMPLETE", bundle.resolve("COMPLETE"))).equals(row.get(7))) {
                throw new IOException("Raw bundle/index mismatch");
            }
        }
    }

    private static void validateReferences(Path root, ClosedLoopCheckpoint checkpoint)
            throws IOException {
        for (var evidence : checkpoint.evidence()) {
            String expectedPath = "evidence/" + evidence.benchmarkRunId();
            if (!evidence.bundle().relativePath().equals(expectedPath)
                    || !virtualFingerprint(Map.of("run.csv", root.resolve(
                    "raw-data/bundles/" + evidence.benchmarkRunId() + "/run.csv"),
                    "policies.csv", root.resolve("raw-data/bundles/"
                            + evidence.benchmarkRunId() + "/policies.csv"),
                    "observations.csv", root.resolve("raw-data/bundles/"
                            + evidence.benchmarkRunId() + "/observations.csv"),
                    "COMPLETE", root.resolve("raw-data/bundles/"
                            + evidence.benchmarkRunId() + "/COMPLETE")))
                    .equals(evidence.bundle().sha256())) {
                throw new IOException("Detached evidence reference mismatch");
            }
        }
        if (checkpoint.calibrationPlan().isPresent()) {
            validateReference(checkpoint.calibrationPlan().orElseThrow(), "calibration-plan",
                    virtualFingerprint(Map.of("fixed-anchors.csv",
                            root.resolve("fixed-anchors.csv"), "reference-runs.csv",
                            root.resolve("reference-runs.csv"))));
        }
        if (checkpoint.latestMerge().isPresent()) {
            Map<String, Path> files = new HashMap<>();
            for (String name : List.of("fixed-anchors.csv", "reference-runs.csv",
                    "calibration-report.csv", "scenario-results.csv", "robust-ranking.csv",
                    "coverage-report.csv")) files.put(name, root.resolve(name));
            files.put("robust-leaders.vectors.csv",
                    root.resolve("vectors/robust-leaders.vectors.csv"));
            files.put("incomplete-policies.vectors.csv",
                    root.resolve("vectors/incomplete-promising.vectors.csv"));
            validateReference(checkpoint.latestMerge().orElseThrow(), "merges/merge-",
                    virtualFingerprint(files));
        }
        if (checkpoint.latestModel().isPresent()) {
            validateReference(checkpoint.latestModel().orElseThrow(), "models/model-",
                    CanonicalFileSupport.sha256(root.resolve("model")));
        }
        if (checkpoint.pendingSchedule().isPresent()) {
            validateReference(checkpoint.pendingSchedule().orElseThrow(),
                    "iterations/iteration-", CanonicalFileSupport.sha256(
                            root.resolve("scheduler")));
        }
    }

    private static void validateReference(ArtifactReference reference, String pathPrefix,
            String actualHash) throws IOException {
        if (!reference.relativePath().startsWith(pathPrefix)
                || !reference.sha256().equals(actualHash)) {
            throw new IOException("Detached artifact reference mismatch");
        }
    }

    private static void validateMerge(Path root, ClosedLoopCheckpoint checkpoint)
            throws IOException {
        if (checkpoint.latestMerge().isEmpty()) return;
        PackageDatasetWriter.validateMeasurements(root);
    }

    private static void validateSchedule(Path root, ClosedLoopCheckpoint checkpoint,
            TrainingRunPackageInputs inputs) throws IOException {
        if (!Files.isDirectory(root.resolve("scheduler"), LinkOption.NOFOLLOW_LINKS)) return;
        var schedule = ScheduleCodec.read(root.resolve("scheduler"),
                checkpoint.requiredScenarios(), checkpoint.trainingRunId(),
                inputs.schedulerSeed(), inputs.commitSha(), inputs.dirtyWorkingTree(),
                inputs.benchmarkConfig());
        List<List<String>> vectors = CanonicalCsv.read(
                root.resolve("vectors/benchmark-ready.vectors.csv"));
        long expected = schedule.runs().stream().mapToLong(run -> run.policies().size()).sum();
        if (vectors.size() - 1L != expected) {
            throw new IOException("Benchmark-ready vector count mismatch");
        }
        PackageDatasetWriter.validateBenchmarkReady(
                root.resolve("vectors/benchmark-ready.vectors.csv"), schedule);
        if (checkpoint.pendingSchedule().isEmpty()) {
            Set<String> completed = checkpoint.evidence().stream().filter(entry ->
                    entry.source() == EvidenceSource.ITERATION)
                    .map(entry -> entry.benchmarkRunId())
                    .collect(java.util.stream.Collectors.toSet());
            for (var run : schedule.runs()) {
                if (!completed.contains(run.benchmarkRunId())) {
                    throw new IOException("Derived schedule lacks checkpoint iteration evidence");
                }
            }
        }
    }

    private static void validateModel(Path root, ClosedLoopCheckpoint checkpoint)
            throws IOException {
        if (!Files.isDirectory(root.resolve("model"), LinkOption.NOFOLLOW_LINKS)) return;
        ScenarioModelMetadata metadata = ScenarioModelMetadataCodec.read(
                root.resolve("model/model-metadata.json"));
        if (!metadata.requiredScenarios().equals(checkpoint.requiredScenarios())) {
            throw new IOException("Model/checkpoint scenario mismatch");
        }
        for (MemberMetadata member : metadata.members()) {
            Path path = root.resolve("model").resolve(member.relativePath()).normalize();
            if (!path.startsWith(root.resolve("model"))
                    || !CanonicalFileSupport.sha256(path).equals(member.sha256())) {
                throw new IOException("Model member checksum mismatch");
            }
        }
        ArrayList<String> expected = new ArrayList<>(List.of("model-metadata.json",
                "grouped-evaluation.csv", "loso-evaluation.csv", "ablation-evaluation.csv",
                "training-history.csv"));
        metadata.members().stream().map(MemberMetadata::relativePath).forEach(expected::add);
        expected.sort(String::compareTo);
        List<String> actual;
        try (var stream = Files.walk(root.resolve("model"))) {
            actual = stream.filter(path -> Files.isRegularFile(path,
                            LinkOption.NOFOLLOW_LINKS))
                    .map(path -> root.resolve("model").relativize(path).toString()
                            .replace('\\', '/')).sorted().toList();
        }
        if (!actual.equals(expected)) {
            throw new IOException("Unexpected model inventory");
        }
    }

    private static void validateOrigins(Path root, TrainingRunManifest manifest)
            throws IOException {
        Map<String, EvidenceOrigin> origins = new HashMap<>();
        List<List<String>> index = CanonicalCsv.read(root.resolve("raw-data/index.csv"));
        for (List<String> row : index.subList(1, index.size())) {
            origins.put(row.get(1), EvidenceOrigin.valueOf(row.get(5)));
        }
        for (PackageFile file : manifest.files()) {
            ArtifactOrigin expected;
            if (file.sourceRunIds().isEmpty()) expected = ArtifactOrigin.NOT_APPLICABLE;
            else {
                boolean nativeFound = false;
                boolean importedFound = false;
                for (String run : file.sourceRunIds()) {
                    EvidenceOrigin origin = origins.get(run);
                    if (origin == null) throw new IOException("Unknown manifest source run");
                    nativeFound |= origin == EvidenceOrigin.NATIVE;
                    importedFound |= origin == EvidenceOrigin.IMPORTED;
                }
                expected = nativeFound && importedFound ? ArtifactOrigin.MIXED
                        : nativeFound ? ArtifactOrigin.UPGRADED_RUN
                        : ArtifactOrigin.IMPORTED_CURRENT_WORKSPACE;
            }
            if (file.origin() != expected) throw new IOException("Manifest origin mismatch");
        }
    }

    private static String virtualFingerprint(Map<String, Path> files) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("directory-artifact-v1\n".getBytes(StandardCharsets.UTF_8));
            for (var entry : files.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                if (!Files.isRegularFile(entry.getValue(), LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(entry.getValue())) {
                    throw new IOException("Virtual artifact file is absent");
                }
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\t');
                digest.update(Long.toString(Files.size(entry.getValue()))
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\t');
                digest.update(CanonicalFileSupport.sha256(entry.getValue())
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static boolean requiresSchedule(ClosedLoopCheckpoint checkpoint) {
        return checkpoint.pendingSchedule().isPresent()
                || checkpoint.nextIteration() > 1
                || switch (checkpoint.stage()) {
                    case SCHEDULE_READY, BENCHMARKING, READY_TO_MERGE, RUN_COMPLETE -> true;
                    default -> false;
                };
    }

    private static final class Counter implements ObservationBundleReader.ObservationVisitor {
        private BenchmarkRunContext context;
        private int policyCount;
        private long observationCount;
        @Override
        public void onStart(BenchmarkRunContext run, List<ScheduledPolicy> policies) {
            context = run;
            policyCount = policies.size();
        }
        @Override
        public void onObservation(BenchmarkObservation observation) {
            observationCount = Math.addExact(observationCount, 1);
        }
    }

    private TrainingRunPackageValidator() {
    }
}
