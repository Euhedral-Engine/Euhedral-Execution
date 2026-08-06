package io.euhedral_execution.training;

import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.merge.AnchorBootstrapper;
import io.euhedral_execution.training.merge.HierarchicalAggregator;
import io.euhedral_execution.training.merge.MergeCsvWriter;
import io.euhedral_execution.training.merge.RunAggregator;
import io.euhedral_execution.training.merge.RunCalibrator;
import io.euhedral_execution.training.merge.ScenarioQualityRanker;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.config.AnchorSelectionConfig;
import io.euhedral_execution.training.merge.config.CalibrationConfig;
import io.euhedral_execution.training.merge.data.AnchorCatalog;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.merge.data.CalibrationPlanCsv;
import io.euhedral_execution.training.merge.data.MergeRecords.MergeResult;
import io.euhedral_execution.training.merge.data.ReferenceRunCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataMerger {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataMerger.class);

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths =
                    new ArrayList<>(stream.sorted(Comparator.reverseOrder()).toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static CalibrationPlan bootstrapCalibrationV1(CalibrationBootstrapRequest request) throws Exception {
        Objects.requireNonNull(request);
        Path target = request.planDirectory().toAbsolutePath().normalize();
        Path temporary = temporarySibling(target);
        ensureNewTarget(target, temporary);
        try {
            PolicyRegistry policies = new PolicyRegistry();
            var runs = RunAggregator.aggregate(request.observationBundles(), policies, request.aggregation());
            CalibrationPlan plan = AnchorBootstrapper.bootstrap(
                    runs,
                    request.requiredScenarios(),
                    request.policyBudget(),
                    request.referenceOverrides(),
                    request.anchorSelection(),
                    request.aggregation());
            CalibrationPlanCsv.write(temporary, plan);
            CalibrationPlan readBack = CalibrationPlanCsv.read(temporary, request.requiredScenarios());
            if (!readBack.anchors().anchorSetId().equals(plan.anchors().anchorSetId())
                    || !readBack.references().equals(plan.references())) {
                LOGGER.error(
                        "Calibration plan validation failed: written anchorSetId={}, readBack anchorSetId={}, referencesMatch={}",
                        plan.anchors().anchorSetId(),
                        readBack.anchors().anchorSetId(),
                        readBack.references().equals(plan.references()));
                throw new IllegalStateException("Calibration plan validation failed");
            }
            publish(temporary, target);
            return plan;
        } catch (Throwable error) {
            deleteRecursively(temporary);
            throw error;
        }
    }

    public static CalibrationPlan mergeCalibrationPlans(MergeCalibrationPlansRequest request) throws Exception {
        Objects.requireNonNull(request);
        Path target = request.outputDirectory().toAbsolutePath().normalize();
        Path temporary = temporarySibling(target);
        ensureNewTarget(target, temporary);
        try {
            ResolvedCalibrationWorkspace merged = mergeCalibrationWorkspace(request.workspaces());
            CalibrationPlan plan = merged.plan();
            CalibrationPlanCsv.write(temporary, plan);
            CalibrationPlan readBack = CalibrationPlanCsv.read(temporary);
            if (!readBack.equals(plan)) {
                LOGGER.error("Merged calibration plan validation failed: written={}, readBack={}", plan, readBack);
                throw new IllegalStateException("Merged calibration plan validation failed");
            }
            Path evidenceDirectory = temporary.resolve("evidence");
            Files.createDirectories(evidenceDirectory);

            for (Path bundle : merged.evidenceBundles()) {
                Path destination = evidenceDirectory.resolve(bundle.getFileName());

                try (var stream = Files.walk(bundle)) {
                    stream.filter(Files::isRegularFile).forEach(sourceFile -> {
                        try {
                            Path relativePath = bundle.relativize(sourceFile);
                            Path dirFile = destination.resolve(relativePath);

                            Files.createDirectories(dirFile.getParent());
                            Files.copy(sourceFile, dirFile, StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }
            publish(temporary, target);
            return plan;
        } catch (Throwable error) {
            deleteRecursively(temporary);
            throw error;
        }
    }

    public static MergeArtifacts merge(MergeRequest request) throws Exception {
        Objects.requireNonNull(request);
        Path target = request.outputDirectory().toAbsolutePath().normalize();
        Path temporary = temporarySibling(target);
        ensureNewTarget(target, temporary);
        try {
            PolicyRegistry policies = new PolicyRegistry();
            var runs = RunAggregator.aggregate(request.observationBundles(), policies, request.aggregation());
            var calibrations = RunCalibrator.calibrate(runs, request.calibrationPlan(), request.calibration());
            var scenarios = HierarchicalAggregator.aggregateScenarios(
                    policies.policiesInIdOrder(),
                    runs,
                    calibrations,
                    request.requiredScenarios(),
                    request.aggregation());
            scenarios = ScenarioQualityRanker.assignQualities(scenarios);
            var summaries = ScenarioQualityRanker.summarize(
                    policies.policiesInIdOrder(), scenarios, request.requiredScenarios());
            MergeResult result = new MergeResult(request.calibrationPlan(), calibrations, scenarios, summaries);
            MergeCsvWriter.write(temporary, result, request.aggregation().calibrationAcceptance());
            validateMergeOutput(temporary, request.requiredScenarios());
            publish(temporary, target);
            return artifacts(target);
        } catch (Throwable error) {
            deleteRecursively(temporary);
            throw error;
        }
    }

    private static Path temporarySibling(Path target) {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Output requires a parent");
        }
        return parent.resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
    }

    private static void ensureNewTarget(Path target, Path temporary) throws Exception {
        if (Files.exists(target)) {
            throw new IllegalArgumentException("Output already exists: " + target);
        }
        Files.createDirectories(target.getParent());
        Files.createDirectory(temporary);
    }

    private static void publish(Path temporary, Path target) throws Exception {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target);
        }
    }

    private static ResolvedCalibrationWorkspace mergeCalibrationWorkspace(List<Path> workspaces) throws Exception {
        SortedMap<PolicyId, PolicyVector> anchorsById = new TreeMap<>();
        SortedMap<SourceScenario, String> references = new TreeMap<>();
        SortedMap<String, Path> bundlesByRunId = new TreeMap<>();
        for (Path workspace : workspaces) {
            Path root = workspace.toAbsolutePath().normalize();
            CalibrationPlan plan = CalibrationPlanCsv.read(root.resolve("calibration-plan"));
            for (PolicyVector policy : plan.anchors().fixedAnchors()) {
                PolicyVector previous = anchorsById.putIfAbsent(policy.id(), policy);
                if (previous != null && !previous.equals(policy)) {
                    throw new IllegalArgumentException("Anchor policy content disagrees across workspaces: "
                            + policy.id().canonical());
                }
            }
            for (Map.Entry<SourceScenario, String> entry :
                    plan.references().referenceRunIds().entrySet()) {
                String previous = references.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null && !previous.equals(entry.getValue())) {
                    throw new IllegalArgumentException("Scenario reference disagrees across workspaces: "
                            + entry.getKey().canonical());
                }
            }
            Set<String> accepted = new HashSet<>();
            Path evidenceDirectory = root.resolve("evidence");
            if (Files.isDirectory(evidenceDirectory)) {
                try (var stream = Files.list(evidenceDirectory)) {
                    for (Path bundle : stream.filter(Files::isDirectory)
                            .filter(path -> Files.isRegularFile(path.resolve("COMPLETE")))
                            .sorted(Comparator.comparing(
                                    path -> path.getFileName().toString()))
                            .toList()) {
                        String runId = ObservationBundleReader.readRunId(bundle);
                        accepted.add(runId);
                        Path normalized = bundle.toAbsolutePath().normalize();
                        Path previous = bundlesByRunId.putIfAbsent(runId, normalized);
                        if (previous != null
                                && !previous.equals(normalized)
                                && !ArtifactFingerprint.sha256(previous)
                                        .equals(ArtifactFingerprint.sha256(normalized))) {
                            throw new IllegalArgumentException("Duplicate evidence bundle for benchmark run " + runId);
                        }
                    }
                }
            }
        }
        references.entrySet().removeIf(entry -> !bundlesByRunId.containsKey(entry.getValue()));
        if (anchorsById.isEmpty()) {
            throw new IllegalArgumentException("At least one workspace is required");
        }
        AnchorCatalog anchors = AnchorCatalog.of(List.copyOf(anchorsById.values()));
        for (String runId : references.values()) {
            if (!bundlesByRunId.containsKey(runId)) {
                throw new IllegalArgumentException("Missing evidence bundle for reference run " + runId);
            }
        }
        CalibrationPlan plan =
                new CalibrationPlan(anchors, new ReferenceRunCatalog(1, anchors.anchorSetId(), references));
        return new ResolvedCalibrationWorkspace(plan, List.copyOf(bundlesByRunId.values()));
    }

    private static void validateMergeOutput(Path directory, SortedSet<SourceScenario> requiredScenarios)
            throws Exception {
        CalibrationPlanCsv.read(directory, requiredScenarios);
        List<String> files = List.of(
                "fixed-anchors.csv",
                "reference-runs.csv",
                "calibration-report.csv",
                "scenario-results.csv",
                "robust-ranking.csv",
                "coverage-report.csv",
                "robust-leaders.vectors.csv",
                "incomplete-policies.vectors.csv");
        for (String file : files) {
            String text = Files.readString(directory.resolve(file));
            if (!text.startsWith("schema_version,") || !text.endsWith("\n")) {
                LOGGER.error(
                        "Invalid merge artifact {}: startsWithSchema={}, endsWithNewline={}",
                        file,
                        text.startsWith("schema_version,"),
                        text.endsWith("\n"));
                throw new IllegalStateException("Invalid merge artifact " + file);
            }
        }
    }

    private static MergeArtifacts artifacts(Path directory) {
        return new MergeArtifacts(
                directory.resolve("fixed-anchors.csv"),
                directory.resolve("reference-runs.csv"),
                directory.resolve("calibration-report.csv"),
                directory.resolve("scenario-results.csv"),
                directory.resolve("robust-ranking.csv"),
                directory.resolve("coverage-report.csv"),
                directory.resolve("robust-leaders.vectors.csv"),
                directory.resolve("incomplete-policies.vectors.csv"));
    }

    private DataMerger() {}

    public record CalibrationBootstrapRequest(
            List<Path> observationBundles,
            SortedSet<SourceScenario> requiredScenarios,
            int policyBudget,
            Map<SourceScenario, String> referenceOverrides,
            Path planDirectory,
            AnchorSelectionConfig anchorSelection,
            AggregationConfig aggregation) {

        public CalibrationBootstrapRequest {
            observationBundles = List.copyOf(observationBundles);
            requiredScenarios = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(requiredScenarios));
            referenceOverrides = Map.copyOf(referenceOverrides);
            Objects.requireNonNull(planDirectory);
            Objects.requireNonNull(anchorSelection);
            Objects.requireNonNull(aggregation);
        }
    }

    public record MergeCalibrationPlansRequest(List<Path> workspaces, Path outputDirectory) {

        public MergeCalibrationPlansRequest {
            Objects.requireNonNull(workspaces);
            workspaces = workspaces.stream()
                    .map(path -> Objects.requireNonNull(path).toAbsolutePath().normalize())
                    .toList();
            if (workspaces.isEmpty()) {
                throw new IllegalArgumentException("At least one workspace is required");
            }
            if (new HashSet<>(workspaces).size() != workspaces.size()) {
                throw new IllegalArgumentException("Duplicate workspace");
            }
            Objects.requireNonNull(outputDirectory);
        }
    }

    public record MergeRequest(
            List<Path> observationBundles,
            SortedSet<SourceScenario> requiredScenarios,
            CalibrationPlan calibrationPlan,
            Path outputDirectory,
            CalibrationConfig calibration,
            AggregationConfig aggregation) {

        public MergeRequest {
            observationBundles = List.copyOf(observationBundles);
            requiredScenarios = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(requiredScenarios));
            Objects.requireNonNull(calibrationPlan);
            Objects.requireNonNull(outputDirectory);
            Objects.requireNonNull(calibration);
            Objects.requireNonNull(aggregation);
        }
    }

    public record MergeArtifacts(
            Path fixedAnchors,
            Path referenceRuns,
            Path calibrationReport,
            Path scenarioResults,
            Path robustRanking,
            Path coverageReport,
            Path robustLeaderVectors,
            Path incompleteVectors) {}

    private record ResolvedCalibrationWorkspace(CalibrationPlan plan, List<Path> evidenceBundles) {}
}
