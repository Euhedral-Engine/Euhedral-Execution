package io.euhedral_execution.training;

import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.AnchorBootstrapper;
import io.euhedral_execution.training.merge.HierarchicalAggregator;
import io.euhedral_execution.training.merge.MergeCsvWriter;
import io.euhedral_execution.training.merge.RunAggregator;
import io.euhedral_execution.training.merge.RunCalibrator;
import io.euhedral_execution.training.merge.ScenarioQualityRanker;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.config.AnchorSelectionConfig;
import io.euhedral_execution.training.merge.config.CalibrationConfig;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.merge.data.CalibrationPlanCsv;
import io.euhedral_execution.training.merge.data.MergeRecords.MergeResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.UUID;
import java.util.stream.Stream;

public class DataMerger {

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(stream.sorted(Comparator.reverseOrder()).toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static CalibrationPlan bootstrapCalibrationV1(
            CalibrationBootstrapRequest request) throws Exception {
        Objects.requireNonNull(request);
        Path target = request.planDirectory().toAbsolutePath().normalize();
        Path temporary = temporarySibling(target);
        ensureNewTarget(target, temporary);
        try {
            PolicyRegistry policies = new PolicyRegistry();
            var runs = RunAggregator.aggregate(request.observationBundles(), policies,
                    request.aggregation());
            CalibrationPlan plan = AnchorBootstrapper.bootstrap(runs, request.requiredScenarios(),
                    request.policyBudget(), request.referenceOverrides(),
                    request.anchorSelection(), request.aggregation());
            CalibrationPlanCsv.write(temporary, plan);
            CalibrationPlan readBack = CalibrationPlanCsv.read(temporary,
                    request.requiredScenarios());
            if (!readBack.anchors().anchorSetId().equals(plan.anchors().anchorSetId())
                    || !readBack.references().equals(plan.references())) {
                throw new IllegalStateException("Calibration plan validation failed");
            }
            publish(temporary, target);
            return plan;
        } catch (Throwable error) {
            deleteRecursively(temporary);
            throw error;
        }
    }

    public static MergeArtifacts mergeV1(MergeRequest request) throws Exception {
        Objects.requireNonNull(request);
        Path target = request.outputDirectory().toAbsolutePath().normalize();
        Path temporary = temporarySibling(target);
        ensureNewTarget(target, temporary);
        try {
            PolicyRegistry policies = new PolicyRegistry();
            var runs = RunAggregator.aggregate(request.observationBundles(), policies,
                    request.aggregation());
            var calibrations = RunCalibrator.calibrate(runs, request.calibrationPlan(),
                    request.calibration());
            var scenarios = HierarchicalAggregator.aggregateScenarios(
                    policies.policiesInIdOrder(), runs, calibrations,
                    request.requiredScenarios(), request.aggregation());
            scenarios = ScenarioQualityRanker.assignQualities(scenarios);
            var summaries = ScenarioQualityRanker.summarize(policies.policiesInIdOrder(),
                    scenarios, request.requiredScenarios());
            MergeResult result = new MergeResult(request.calibrationPlan(), calibrations,
                    scenarios, summaries);
            MergeCsvWriter.write(temporary, result,
                    request.aggregation().calibrationAcceptance());
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

    private static void validateMergeOutput(Path directory,
            SortedSet<SourceScenario> requiredScenarios) throws Exception {
        CalibrationPlanCsv.read(directory, requiredScenarios);
        List<String> files = List.of("fixed-anchors.csv", "reference-runs.csv",
                "calibration-report.csv", "scenario-results.csv", "robust-ranking.csv",
                "coverage-report.csv", "robust-leaders.vectors.csv",
                "incomplete-policies.vectors.csv");
        for (String file : files) {
            String text = Files.readString(directory.resolve(file));
            if (!text.startsWith("schema_version,") || !text.endsWith("\n")) {
                throw new IllegalStateException("Invalid merge artifact " + file);
            }
        }
    }

    private static MergeArtifacts artifacts(Path directory) {
        return new MergeArtifacts(directory.resolve("fixed-anchors.csv"),
                directory.resolve("reference-runs.csv"),
                directory.resolve("calibration-report.csv"),
                directory.resolve("scenario-results.csv"),
                directory.resolve("robust-ranking.csv"),
                directory.resolve("coverage-report.csv"),
                directory.resolve("robust-leaders.vectors.csv"),
                directory.resolve("incomplete-policies.vectors.csv"));
    }

    private DataMerger() {

    }

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
            requiredScenarios = java.util.Collections.unmodifiableSortedSet(
                    new java.util.TreeSet<>(requiredScenarios));
            referenceOverrides = Map.copyOf(referenceOverrides);
            Objects.requireNonNull(planDirectory);
            Objects.requireNonNull(anchorSelection);
            Objects.requireNonNull(aggregation);
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
            requiredScenarios = java.util.Collections.unmodifiableSortedSet(
                    new java.util.TreeSet<>(requiredScenarios));
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
            Path incompleteVectors) {

    }

}
