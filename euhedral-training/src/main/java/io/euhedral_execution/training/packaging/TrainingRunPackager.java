package io.euhedral_execution.training.packaging;

import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import io.euhedral_execution.training.checkpoint.WorkspaceLock;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageRequest;
import io.euhedral_execution.training.packaging.data.TrainingRunPackage;
import io.euhedral_execution.training.packaging.enums.ArtifactOrigin;
import io.euhedral_execution.training.packaging.enums.ArtifactSemanticType;
import io.euhedral_execution.training.packaging.enums.ProducingStage;
import io.euhedral_execution.training.packaging.enums.TrainingRunPackageStatus;
import io.euhedral_execution.training.packaging.io.TrainingRunPackageInputsCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TrainingRunPackager {

    private static final List<String> MERGE_ONLY_COPIES =
            List.of("calibration-report.csv", "scenario-results.csv", "robust-ranking.csv", "coverage-report.csv");

    public static TrainingRunPackage publish(TrainingRunPackageRequest request) throws IOException {
        return publish(request, PublicationProbe.NO_OP);
    }

    static TrainingRunPackage publish(TrainingRunPackageRequest request, PublicationProbe probe) throws IOException {
        Path outputRoot = request.outputRoot();
        Path target = outputRoot.resolve("training-run-" + request.inputs().packageId());
        CanonicalFileSupport.rejectSymlinkComponents(request.workspace());
        CanonicalFileSupport.rejectSymlinkComponents(outputRoot);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return validateCollision(target, request);
        }
        Files.createDirectories(outputRoot);
        Path staging = outputRoot.resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
        try (WorkspaceLock ignored = WorkspaceLock.acquire(request.workspace())) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return validateCollision(target, request);
            }
            cleanupOwnedStaging(
                    outputRoot,
                    target.getFileName().toString(),
                    request.inputs().packageId(),
                    null);
            Files.createDirectory(staging);
            Path marker = staging.resolve(".euhedral-package-staging");
            Files.writeString(marker, request.inputs().packageId() + "\n", StandardCharsets.UTF_8);
            try {
                PackageSourceSet source = PackageSourceSet.resolve(request);
                probe.at(PublicationPoint.AFTER_SOURCE_VALIDATION);
                stage(source, request.inputs(), staging, probe);
                Files.delete(marker);
                probe.at(PublicationPoint.BEFORE_MANIFEST);
                TrainingRunManifest intended = manifest(source, request.inputs(), staging);
                CanonicalFileSupport.write(staging.resolve("manifest.json"), PackageManifestCodec.encode(intended));
                CanonicalFileSupport.forceTree(staging);
                probe.at(PublicationPoint.DURING_STAGED_VALIDATION);
                TrainingRunPackage validated = TrainingRunPackageValidator.validate(staging);
                TrainingRunManifest actual = PackageManifestCodec.read(validated.manifest());
                if (!actual.equals(intended)) {
                    throw new IOException("Staged package manifest differs from intent");
                }
                probe.at(PublicationPoint.ATOMIC_MOVE);
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException error) {
                    throw new IOException("Atomic package publication is required", error);
                }
                return new TrainingRunPackage(
                        target, target.resolve("manifest.json"), intended.packageId(), intended.status());
            } catch (Throwable error) {
                CanonicalFileSupport.deleteOwnedTree(staging);
                throw error;
            }
        }
    }

    private static void stage(
            PackageSourceSet source, TrainingRunPackageInputs inputs, Path staging, PublicationProbe probe)
            throws IOException {
        Files.createDirectories(staging.resolve("vectors"));
        Files.createDirectories(staging.resolve("reports"));
        Files.createDirectories(staging.resolve("checkpoints/latest"));
        Files.createDirectories(staging.resolve("provenance"));
        Files.createDirectories(staging.resolve("raw-data/bundles"));
        CanonicalFileSupport.copyDirectory(source.loaded().snapshotDirectory(), staging.resolve("checkpoints/latest"));
        probe.at(PublicationPoint.DURING_COPY);
        if (source.calibrationPlan() != null) {
            CanonicalFileSupport.copy(
                    source.calibrationPlan().resolve("fixed-anchors.csv"), staging.resolve("fixed-anchors.csv"));
            CanonicalFileSupport.copy(
                    source.calibrationPlan().resolve("reference-runs.csv"), staging.resolve("reference-runs.csv"));
        }
        if (source.merge() != null) {
            for (String name : MERGE_ONLY_COPIES) {
                CanonicalFileSupport.copy(source.merge().resolve(name), staging.resolve(name));
            }
            CanonicalFileSupport.copy(
                    source.merge().resolve("robust-leaders.vectors.csv"),
                    staging.resolve("vectors/robust-leaders.vectors.csv"));
            CanonicalFileSupport.copy(
                    source.merge().resolve("incomplete-policies.vectors.csv"),
                    staging.resolve("vectors/incomplete-promising.vectors.csv"));
            PackageDatasetWriter.writeMeasurements(source.merge(), staging.resolve("policy-scenario-measurements.csv"));
        }
        if (source.model() != null) {
            CanonicalFileSupport.copyDirectory(source.model(), staging.resolve("model"));
        }
        if (source.schedule() != null) {
            CanonicalFileSupport.copyDirectory(source.schedule(), staging.resolve("scheduler"));
            PackageDatasetWriter.writeBenchmarkReady(source, staging.resolve("vectors/benchmark-ready.vectors.csv"));
        }
        for (PackageSourceSet.EvidenceInfo evidence : source.evidence()) {
            CanonicalFileSupport.copyDirectory(
                    evidence.directory(),
                    staging.resolve("raw-data/bundles/")
                            .resolve(evidence.index().benchmarkRunId()));
        }
        PackageDatasetWriter.writeRawIndex(source, staging.resolve("raw-data/index.csv"));
        CanonicalFileSupport.write(
                staging.resolve("provenance/package-inputs.properties"), TrainingRunPackageInputsCodec.encode(inputs));
        PackageReportWriter.write(source, inputs, staging);
    }

    private static TrainingRunManifest manifest(PackageSourceSet source, TrainingRunPackageInputs inputs, Path staging)
            throws IOException {
        ArrayList<PackageFile> files = new ArrayList<>();
        try (var stream = Files.walk(staging)) {
            for (Path path : stream.filter(item -> Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(
                            item -> staging.relativize(item).toString().replace('\\', '/')))
                    .toList()) {
                String relative = staging.relativize(path).toString().replace('\\', '/');
                if (relative.equals("manifest.json") || relative.equals(".euhedral-package-staging")) continue;
                files.add(file(source, path, relative));
            }
        }
        var checkpoint = source.loaded().checkpoint();
        return new TrainingRunManifest(
                inputs.packageId(),
                inputs.trainingRunId(),
                inputs.checkpointRevision(),
                checkpoint.stage(),
                source.status(),
                source.status() == TrainingRunPackageStatus.COMPLETE,
                checkpoint.configSha256(),
                ArtifactFingerprint.sha256(source.loaded().snapshotDirectory()),
                inputs.commitSha(),
                inputs.dirtyWorkingTree(),
                List.copyOf(checkpoint.requiredScenarios()),
                source.calibrationAcceptance(),
                source.winners(),
                files,
                source.omissions());
    }

    private static PackageFile file(PackageSourceSet source, Path file, String relative) throws IOException {
        Classification classification = classify(relative);
        CanonicalFileSupport.CsvMetadata csv =
                relative.endsWith(".csv") ? CanonicalFileSupport.csvMetadata(file) : null;
        List<String> runIds = sourceRuns(source, relative);
        return new PackageFile(
                relative,
                classification.semanticType(),
                mediaType(relative),
                csv == null ? null : csv.schemaVersion(),
                csv == null ? null : csv.rowCount(),
                CanonicalFileSupport.sha256(file),
                classification.producingStage(),
                runIds,
                origin(source, runIds),
                true);
    }

    static Classification classify(String path) {
        if (path.equals("README.md")) return c(ArtifactSemanticType.PACKAGE_README, ProducingStage.PACKAGE);
        if (path.startsWith("reports/")) return c(ArtifactSemanticType.HUMAN_READABLE_REPORT, ProducingStage.PACKAGE);
        if (path.equals("policy-scenario-measurements.csv"))
            return c(ArtifactSemanticType.VECTOR_WITH_MEASUREMENTS_DATASET, ProducingStage.PACKAGE);
        if (path.startsWith("vectors/"))
            return c(
                    ArtifactSemanticType.VECTOR_ONLY_DATASET,
                    path.endsWith("benchmark-ready.vectors.csv") ? ProducingStage.PACKAGE : ProducingStage.MERGE);
        if (path.equals("provenance/package-inputs.properties"))
            return c(ArtifactSemanticType.PACKAGE_REPRODUCTION_INPUT, ProducingStage.PACKAGE);
        if (path.equals("raw-data/index.csv")) return c(ArtifactSemanticType.RAW_DATA_INDEX, ProducingStage.PACKAGE);
        if (path.startsWith("raw-data/bundles/")) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return switch (name) {
                case "run.csv" -> c(ArtifactSemanticType.RAW_RUN_METADATA, ProducingStage.BENCHMARK_EVIDENCE);
                case "policies.csv" -> c(ArtifactSemanticType.RAW_POLICY_CATALOG, ProducingStage.BENCHMARK_EVIDENCE);
                case "observations.csv" -> c(ArtifactSemanticType.RAW_OBSERVATIONS, ProducingStage.BENCHMARK_EVIDENCE);
                case "COMPLETE" -> c(ArtifactSemanticType.COMPLETION_MARKER, ProducingStage.BENCHMARK_EVIDENCE);
                default -> throw new IllegalArgumentException("Unexpected raw artifact");
            };
        }
        if (path.startsWith("checkpoints/latest/")) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.equals("COMPLETE")) return c(ArtifactSemanticType.COMPLETION_MARKER, ProducingStage.CHECKPOINT);
            return c(
                    name.equals("state.csv")
                            ? ArtifactSemanticType.CHECKPOINT_STATE
                            : ArtifactSemanticType.CHECKPOINT_SIDECAR,
                    ProducingStage.CHECKPOINT);
        }
        if (path.startsWith("scheduler/")) {
            return c(
                    path.endsWith("/COMPLETE")
                            ? ArtifactSemanticType.COMPLETION_MARKER
                            : ArtifactSemanticType.SCHEDULE_DATASET,
                    ProducingStage.SCHEDULING);
        }
        if (path.startsWith("model/")) {
            if (path.equals("model/model-metadata.json"))
                return c(ArtifactSemanticType.MODEL_METADATA, ProducingStage.LEARNING);
            if (path.endsWith(".index") || path.contains(".data-")) {
                return c(ArtifactSemanticType.MODEL_MEMBER_PARAMETERS, ProducingStage.LEARNING);
            }
            return c(ArtifactSemanticType.MODEL_EVALUATION_DATASET, ProducingStage.LEARNING);
        }
        if (Set.of(
                        "fixed-anchors.csv",
                        "reference-runs.csv",
                        "robust-ranking.csv",
                        "scenario-results.csv",
                        "calibration-report.csv",
                        "coverage-report.csv")
                .contains(path)) {
            return c(ArtifactSemanticType.MERGE_DATASET, ProducingStage.MERGE);
        }
        throw new IllegalArgumentException("Unexpected package artifact " + path);
    }

    private static List<String> sourceRuns(PackageSourceSet source, String path) {
        if (path.equals("provenance/package-inputs.properties")) return List.of();
        if (path.startsWith("raw-data/bundles/")) {
            return List.of(path.split("/")[2]);
        }
        if (path.startsWith("model/") && source.model() != null) {
            int iteration = artifactIteration(source.model(), "model-");
            return source.evidence().stream()
                    .filter(item -> item.context().descriptor().closedLoopIteration() < iteration)
                    .map(item -> item.index().benchmarkRunId())
                    .sorted()
                    .toList();
        }
        if ((path.startsWith("scheduler/") || path.endsWith("benchmark-ready.vectors.csv"))
                && source.scheduleData() != null) {
            int iteration = source.scheduleData().iteration();
            return source.evidence().stream()
                    .filter(item -> item.context().descriptor().closedLoopIteration() < iteration)
                    .map(item -> item.index().benchmarkRunId())
                    .sorted()
                    .toList();
        }
        return source.evidence().stream()
                .map(item -> item.index().benchmarkRunId())
                .sorted()
                .toList();
    }

    private static int artifactIteration(Path path, String prefix) {
        String name = path.getFileName().toString();
        if (!name.matches(prefix + "[0-9]{6}")) {
            throw new IllegalArgumentException("Artifact iteration path is noncanonical");
        }
        return Integer.parseInt(name.substring(prefix.length()));
    }

    private static ArtifactOrigin origin(PackageSourceSet source, List<String> runIds) {
        if (runIds.isEmpty()) return ArtifactOrigin.NOT_APPLICABLE;
        boolean nativeFound = false;
        boolean importedFound = false;
        for (String runId : runIds) {
            EvidenceOrigin origin = source.evidence().stream()
                    .filter(item -> item.index().benchmarkRunId().equals(runId))
                    .findFirst()
                    .orElseThrow()
                    .origin();
            nativeFound |= origin == EvidenceOrigin.NATIVE;
            importedFound |= origin == EvidenceOrigin.IMPORTED;
        }
        if (nativeFound && importedFound) return ArtifactOrigin.MIXED;
        return nativeFound ? ArtifactOrigin.UPGRADED_RUN : ArtifactOrigin.IMPORTED_CURRENT_WORKSPACE;
    }

    static String mediaType(String path) {
        if (path.endsWith(".csv")) return "text/csv";
        if (path.endsWith(".md")) return "text/markdown";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".properties")) return "text/plain";
        if (path.endsWith(".index") || path.contains(".data-")) {
            return "application/octet-stream";
        }
        return "application/octet-stream";
    }

    private static Classification c(ArtifactSemanticType type, ProducingStage stage) {
        return new Classification(type, stage);
    }

    private static TrainingRunPackage validateCollision(Path target, TrainingRunPackageRequest request)
            throws IOException {
        TrainingRunPackageInputs inputs = request.inputs();
        TrainingRunPackage existing;
        try {
            existing = TrainingRunPackageValidator.validate(target);
            TrainingRunManifest manifest = PackageManifestCodec.read(existing.manifest());
            TrainingRunPackageInputs packaged =
                    TrainingRunPackageInputsCodec.read(target.resolve("provenance/package-inputs.properties"));
            PackageSourceSet source = PackageSourceSet.resolve(request);
            if (!manifest.packageId().equals(inputs.packageId())
                    || !manifest.trainingRunId().equals(inputs.trainingRunId())
                    || manifest.checkpointRevision() != inputs.checkpointRevision()
                    || !packaged.equals(inputs)
                    || !manifest.checkpointSha256()
                            .equals(ArtifactFingerprint.sha256(source.loaded().snapshotDirectory()))
                    || manifest.checkpointStage()
                            != source.loaded().checkpoint().stage()
                    || manifest.status() != source.status()
                    || !java.util.Objects.equals(manifest.calibrationAcceptance(), source.calibrationAcceptance())
                    || !manifest.winningPolicyIds().equals(source.winners())
                    || !manifest.omissions().equals(source.omissions())) {
                throw new PackageCollisionException("Existing package identity differs");
            }
            return existing;
        } catch (PackageCollisionException error) {
            throw error;
        } catch (Exception error) {
            throw new PackageCollisionException("Existing package is invalid or differs");
        }
    }

    private static void cleanupOwnedStaging(Path outputRoot, String targetName, String packageId, Path current)
            throws IOException {
        try (var stream = Files.list(outputRoot)) {
            for (Path candidate : stream.filter(
                            path -> path.getFileName().toString().startsWith("." + targetName + ".tmp-"))
                    .toList()) {
                if (candidate.equals(current)) continue;
                Path marker = candidate.resolve(".euhedral-package-staging");
                if (Files.isSymbolicLink(candidate)
                        || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(marker)
                        || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                        || !Files.readString(marker, StandardCharsets.UTF_8).equals(packageId + "\n")) {
                    throw new PackageCollisionException("Unowned or ambiguous staging directory exists");
                }
                CanonicalFileSupport.deleteOwnedTree(candidate);
            }
        }
    }

    record Classification(ArtifactSemanticType semanticType, ProducingStage producingStage) {}

    enum PublicationPoint {
        AFTER_SOURCE_VALIDATION,
        DURING_COPY,
        BEFORE_MANIFEST,
        DURING_STAGED_VALIDATION,
        ATOMIC_MOVE
    }

    @FunctionalInterface
    interface PublicationProbe {
        PublicationProbe NO_OP = point -> {};

        void at(PublicationPoint point) throws IOException;
    }

    private TrainingRunPackager() {}
}
