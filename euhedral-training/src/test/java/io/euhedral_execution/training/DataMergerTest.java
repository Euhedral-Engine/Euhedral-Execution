package io.euhedral_execution.training;

import static io.euhedral_execution.training.fixtures.SyntheticObservations.START;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.policy;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.run;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.writeSuccessBundle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.config.CalibrationConfig;
import io.euhedral_execution.training.merge.data.AnchorCatalog;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.merge.data.CalibrationPlanCsv;
import io.euhedral_execution.training.merge.data.ReferenceRunCatalog;
import io.euhedral_execution.training.scheduling.io.OptimizationCorpusReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataMergerTest {
    @TempDir
    Path temporary;

    @Test
    void writesDeterministicHierarchicalArtifactsAndRanksRobustPolicyFirst() throws Exception {
        Corpus corpus = corpus();
        Path first = temporary.resolve("merge-first");
        Path second = temporary.resolve("merge-second");
        DataMerger.MergeArtifacts artifacts =
                DataMerger.merge(request(corpus.bundles, first, corpus.plan, corpus.scenarios));
        List<Path> reversed = new ArrayList<>(corpus.bundles);
        Collections.reverse(reversed);
        DataMerger.merge(request(reversed, second, corpus.plan, corpus.scenarios));

        List<String> names = List.of(
                "fixed-anchors.csv",
                "reference-runs.csv",
                "calibration-report.csv",
                "scenario-results.csv",
                "robust-ranking.csv",
                "coverage-report.csv",
                "robust-leaders.vectors.csv",
                "incomplete-policies.vectors.csv");
        for (String name : names) {
            assertThat(first.resolve(name)).isRegularFile();
            assertThat(Files.readString(first.resolve(name))).startsWith("schema_version,");
            assertThat(Files.readAllBytes(first.resolve(name))).isEqualTo(Files.readAllBytes(second.resolve(name)));
        }
        Map<String, String> headers = Map.of(
                "calibration-report.csv",
                        "schema_version,calibration_acceptance,scenario_id,benchmark_run_id,reference_run_id,anchor_set_id,fixed_anchor_count,shared_anchor_count,delta_log,scale_factor,weighted_median_absolute_residual,status,reason",
                "scenario-results.csv",
                        "schema_version,scenario_id,environment_id,source_count,available_physical_core_count,source_ratio_numerator,source_ratio_denominator,policy_id,status,total_run_count,accepted_run_count,weak_run_count,uncalibrated_run_count,successful_repetition_count,planned_repetition_count,throughput_p25,throughput_median,throughput_p75,throughput_iqr,median_within_run_relative_iqr,mean_timeout_rate,mean_failure_rate,mean_non_success_rate,bootstrap_median_ci_low,bootstrap_median_ci_high,quality",
                "robust-ranking.csv",
                        "schema_version,published_rank,policy_id,eligible,required_scenario_count,observed_required_scenario_count,valid_required_scenario_count,coverage_fraction,worst_quality,quality_p25,geometric_mean_quality,cross_scenario_quality_mad,median_relative_iqr,mean_non_success_rate,mean_timeout_rate,missing_scenarios",
                "coverage-report.csv",
                        "schema_version,policy_id,eligible,required_scenario_count,observed_required_scenario_count,valid_required_scenario_count,measured_scenarios,missing_scenarios,rejected_scenarios");
        headers.forEach((name, header) -> {
            try {
                assertThat(Files.readString(first.resolve(name)).lines().findFirst())
                        .hasValue(header);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
        String ranking = Files.readString(artifacts.robustRanking());
        assertThat(ranking).contains("\n1,1," + corpus.robust.id().canonical() + ",true,");
        assertThat(ranking).contains("," + corpus.specialist.id().canonical() + ",false,");
        assertThat(ranking).contains("," + corpus.incomplete.id().canonical() + ",false,");
        String scenarios = Files.readString(artifacts.scenarioResults());
        long dataRows = scenarios.lines().skip(1).count();
        assertThat(dataRows).isEqualTo(8L * 4L);
        assertThat(scenarios).contains("VALID_STRONG").contains("MISSING");
        String calibrations = Files.readString(artifacts.calibrationReport());
        assertThat(calibrations).contains(",2.0,0.0,CALIBRATED,STRONG").contains(",0.5,0.0,CALIBRATED,STRONG");
        String coverage = Files.readString(artifacts.coverageReport());
        String specialistCoverage = coverage.lines()
                .filter(line -> line.startsWith("1," + corpus.specialist.id().canonical() + ","))
                .findFirst()
                .orElseThrow();
        String expectedMissing = corpus.scenarios.stream()
                .skip(1)
                .map(SourceScenario::canonical)
                .reduce((left, right) -> left + ";" + right)
                .orElseThrow();
        assertThat(specialistCoverage).contains("," + expectedMissing + ",");
        assertThat(Files.readString(artifacts.robustLeaderVectors()))
                .startsWith("schema_version,robust_rank,policy_id,weight_00_bits");
        assertThat(Files.readString(artifacts.incompleteVectors()))
                .startsWith("schema_version,valid_required_scenario_count");
        var optimizerCorpus = OptimizationCorpusReader.read(artifacts, corpus.scenarios);
        assertThat(optimizerCorpus.eligiblePolicies().getFirst().policy().id()).isEqualTo(corpus.robust.id());
        assertThat(optimizerCorpus.policies())
                .containsKeys(corpus.robust.id(), corpus.specialist.id(), corpus.incomplete.id());
    }

    @Test
    void mergesWorkspaceCalibrationPlansIntoOnePlan() throws Exception {
        Corpus corpus = corpus();
        List<SourceScenario> scenarios = new ArrayList<>(corpus.scenarios);

        Path workspaceA = temporary.resolve("workspace-a");
        Path workspaceB = temporary.resolve("workspace-b");
        CalibrationPlan planA = subsetPlan(corpus.plan, scenarios.subList(0, 2));
        CalibrationPlan planB = subsetPlan(corpus.plan, scenarios.subList(2, 4));
        writeWorkspaceCalibration(workspaceA, planA);
        writeWorkspaceCalibration(workspaceB, planB);
        populateReferenceEvidence(workspaceA, corpus.bundles, planA);
        populateReferenceEvidence(workspaceB, corpus.bundles, planB);

        Path output = temporary.resolve("merged-calibration-plan");
        CalibrationPlan merged = DataMerger.mergeCalibrationPlans(
                new DataMerger.MergeCalibrationPlansRequest(List.of(workspaceA, workspaceB), output));

        assertThat(merged).isEqualTo(corpus.plan);
        assertThat(CalibrationPlanCsv.read(output)).isEqualTo(corpus.plan);
        assertThat(output.resolve("evidence")).isDirectory();
        assertThat(output.resolve("evidence").resolve("ref-0")).exists();
        assertThat(output.resolve("evidence").resolve("ref-2")).exists();
    }

    @Test
    void rejectsConflictingScenarioReferencesWhenMergingWorkspacePlans() throws Exception {
        Corpus corpus = corpus();
        SourceScenario scenario = corpus.scenarios.first();

        Path workspaceA = temporary.resolve("conflict-a");
        Path workspaceB = temporary.resolve("conflict-b");
        writeWorkspaceCalibration(workspaceA, subsetPlan(corpus.plan, List.of(scenario)));

        SortedMap<SourceScenario, String> conflictingReferences = new TreeMap<>();
        conflictingReferences.put(scenario, "other-reference");
        writeWorkspaceCalibration(
                workspaceB,
                new CalibrationPlan(
                        corpus.plan.anchors(),
                        new ReferenceRunCatalog(1, corpus.plan.anchors().anchorSetId(), conflictingReferences)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataMerger.mergeCalibrationPlans(new DataMerger.MergeCalibrationPlansRequest(
                        List.of(workspaceA, workspaceB), temporary.resolve("conflicted-plan"))))
                .withMessageContaining("Scenario reference disagrees across workspaces");
    }

    @Test
    void mergesOverlappingAnchorCatalogsWithoutDuplicateAnchors() throws Exception {
        PolicyVector a1 = policy(101);
        PolicyVector a2 = policy(102);
        PolicyVector shared = policy(103);
        PolicyVector b1 = policy(104);
        PolicyVector b2 = policy(105);
        PolicyVector a3 = policy(106);
        PolicyVector b3 = policy(107);

        Path workspaceA = temporary.resolve("anchors-a");
        Path workspaceB = temporary.resolve("anchors-b");

        SourceScenario scenarioA = SourceScenario.of("host-a", 1, 32);
        SourceScenario scenarioB = SourceScenario.of("host-b", 1, 32);

        AnchorCatalog catalogA = AnchorCatalog.of(List.of(a1, a2, shared, a3));
        AnchorCatalog catalogB = AnchorCatalog.of(List.of(shared, b1, b2, b3));
        writeWorkspaceCalibration(
                workspaceA,
                new CalibrationPlan(
                        catalogA,
                        new ReferenceRunCatalog(1, catalogA.anchorSetId(), new TreeMap<>(Map.of(scenarioA, "ref-a")))));
        writeWorkspaceCalibration(
                workspaceB,
                new CalibrationPlan(
                        catalogB,
                        new ReferenceRunCatalog(1, catalogB.anchorSetId(), new TreeMap<>(Map.of(scenarioB, "ref-b")))));
        writeReferenceBundle(workspaceA, "ref-a", scenarioA, List.of(a1, a2, shared, a3));
        writeReferenceBundle(workspaceB, "ref-b", scenarioB, List.of(shared, b1, b2, b3));

        CalibrationPlan merged = DataMerger.mergeCalibrationPlans(new DataMerger.MergeCalibrationPlansRequest(
                List.of(workspaceA, workspaceB), temporary.resolve("merged-overlapping-anchors")));

        assertThat(merged.anchors().fixedAnchors()).containsExactlyInAnyOrder(a1, a2, shared, a3, b1, b2, b3);
    }

    @Test
    void dedupesMatchingReferenceEvidenceAcrossInputs() throws Exception {
        Corpus corpus = corpus();
        SourceScenario scenario = corpus.scenarios.first();
        String runId = corpus.plan.references().referenceRunIds().get(scenario);
        Path sourceBundle = findBundleByRunId(corpus.bundles, runId);

        Path workspaceA = temporary.resolve("dedupe-a");
        Path workspaceB = temporary.resolve("dedupe-b");
        writeWorkspaceCalibration(workspaceA, subsetPlan(corpus.plan, List.of(scenario)));
        writeWorkspaceCalibration(workspaceB, subsetPlan(corpus.plan, List.of(scenario)));
        copyBundle(sourceBundle, workspaceA.resolve("evidence").resolve(runId));
        copyBundle(sourceBundle, workspaceB.resolve("evidence").resolve(runId));

        Path output = temporary.resolve("deduped-evidence-plan");
        DataMerger.mergeCalibrationPlans(
                new DataMerger.MergeCalibrationPlansRequest(List.of(workspaceA, workspaceB), output));

        Path mergedBundle = output.resolve("evidence").resolve(runId);
        assertThat(mergedBundle).exists();
        assertThat(ArtifactFingerprint.sha256(mergedBundle)).isEqualTo(ArtifactFingerprint.sha256(sourceBundle));
    }

    @Test
    void rejectsConflictingDuplicateReferenceEvidenceAcrossInputs() throws Exception {
        Corpus corpus = corpus();
        SourceScenario scenario = corpus.scenarios.first();
        String runId = corpus.plan.references().referenceRunIds().get(scenario);
        Path sourceBundle = findBundleByRunId(corpus.bundles, runId);

        Path workspaceA = temporary.resolve("evidence-conflict-a");
        Path workspaceB = temporary.resolve("evidence-conflict-b");
        writeWorkspaceCalibration(workspaceA, subsetPlan(corpus.plan, List.of(scenario)));
        writeWorkspaceCalibration(workspaceB, subsetPlan(corpus.plan, List.of(scenario)));
        copyBundle(sourceBundle, workspaceA.resolve("evidence").resolve(runId));
        Path conflicting =
                copyBundle(sourceBundle, workspaceB.resolve("evidence").resolve(runId));
        Files.writeString(
                conflicting.resolve("run.csv"),
                Files.readString(conflicting.resolve("run.csv")).replaceFirst("false", "true"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataMerger.mergeCalibrationPlans(new DataMerger.MergeCalibrationPlansRequest(
                        List.of(workspaceA, workspaceB), temporary.resolve("evidence-conflict-output"))))
                .withMessageContaining("Duplicate evidence bundle for benchmark run " + runId);
    }

    @Test
    void mergePrunesReferencesWithMissingEvidenceFromDifferentWorkspaces() throws Exception {
        PolicyVector a1 = policy(201);
        PolicyVector a2 = policy(202);
        PolicyVector a3 = policy(203);
        PolicyVector b1 = policy(204);
        PolicyVector b2 = policy(205);
        PolicyVector b3 = policy(206);

        Path workspaceA = temporary.resolve("distinct-anchors-a");
        Path workspaceB = temporary.resolve("distinct-anchors-b");

        SourceScenario scenarioA = SourceScenario.of("host-a", 1, 32);
        SourceScenario scenarioB = SourceScenario.of("host-b", 4, 32);

        AnchorCatalog catalogA = AnchorCatalog.of(List.of(a1, a2, a3));
        AnchorCatalog catalogB = AnchorCatalog.of(List.of(b1, b2, b3));
        writeWorkspaceCalibration(
                workspaceA,
                new CalibrationPlan(
                        catalogA,
                        new ReferenceRunCatalog(1, catalogA.anchorSetId(), new TreeMap<>(Map.of(scenarioA, "ref-a")))));
        writeWorkspaceCalibration(
                workspaceB,
                new CalibrationPlan(
                        catalogB,
                        new ReferenceRunCatalog(1, catalogB.anchorSetId(), new TreeMap<>(Map.of(scenarioB, "ref-b")))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataMerger.mergeCalibrationPlans(new DataMerger.MergeCalibrationPlansRequest(
                        List.of(workspaceA, workspaceB), temporary.resolve("merged-distinct-anchors"))))
                .withMessageContaining("ReferenceRunIds is empty");
    }

    @Test
    void invalidDuplicateCorpusPublishesNothing() throws Exception {
        Corpus corpus = corpus();
        List<Path> duplicate = new ArrayList<>(corpus.bundles);
        duplicate.add(corpus.bundles.getFirst());
        Path output = temporary.resolve("must-not-exist");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataMerger.merge(request(duplicate, output, corpus.plan, corpus.scenarios)));
        assertThat(output).doesNotExist();
    }

    @Test
    void incompatibleSchemaAndPolicyMismatchPublishNothing() throws Exception {
        Corpus corpus = corpus();
        for (String kind : List.of("schema", "policy")) {
            Path changed = copyBundle(corpus.bundles.getFirst(), temporary.resolve("tampered-" + kind));
            Path file = changed.resolve(kind.equals("schema") ? "observations.csv" : "policies.csv");
            String text = Files.readString(file);
            text = kind.equals("schema")
                    ? text.replaceFirst("(?m)^1,", "2,")
                    : text.replaceFirst("p1-[0-9a-f]{16}", "p1-0000000000000000");
            Files.writeString(file, text);
            List<Path> bundles = new ArrayList<>(corpus.bundles);
            bundles.set(0, changed);
            Path output = temporary.resolve("invalid-" + kind);
            assertThatRuntimeException()
                    .isThrownBy(() -> DataMerger.merge(request(bundles, output, corpus.plan, corpus.scenarios)));
            assertThat(output).doesNotExist();
        }
    }

    private DataMerger.MergeRequest request(
            List<Path> bundles, Path output, CalibrationPlan plan, SortedSet<SourceScenario> scenarios) {
        return new DataMerger.MergeRequest(
                bundles, scenarios, plan, output, CalibrationConfig.defaults(), AggregationConfig.defaults());
    }

    private Corpus corpus() {
        List<PolicyVector> anchors = List.of(policy(1), policy(2), policy(3), policy(4), policy(5));
        PolicyVector robust = policy(10);
        PolicyVector specialist = policy(11);
        PolicyVector incomplete = policy(12);
        SortedSet<SourceScenario> scenarios = new TreeSet<>(Set.of(
                SourceScenario.of("host-a", 1, 32), SourceScenario.of("host-a", 4, 32),
                SourceScenario.of("host-b", 1, 32), SourceScenario.of("host-b", 4, 32)));
        double[][] anchorValues = {
            {100, 10, 100, 100}, {100, 100, 10, 100}, {100, 100, 100, 10}, {10, 100, 100, 100}, {20, 20, 100, 100}
        };
        List<Path> bundles = new ArrayList<>();
        SortedMap<SourceScenario, String> references = new TreeMap<>();
        int scenarioIndex = 0;
        for (SourceScenario scenario : scenarios) {
            List<PolicyVector> scheduled = new ArrayList<>(anchors);
            scheduled.add(robust);
            if (scenarioIndex == 0) {
                scheduled.add(specialist);
                scheduled.add(incomplete);
            } else if (scenarioIndex == 1) {
                scheduled.add(incomplete);
            }
            String referenceId = "ref-" + scenarioIndex;
            references.put(scenario, referenceId);
            Map<PolicyId, Double> base = new HashMap<>();
            for (int i = 0; i < anchors.size(); i++) {
                base.put(anchors.get(i).id(), anchorValues[i][scenarioIndex]);
            }
            base.put(robust.id(), 90.0);
            if (scenarioIndex == 0) {
                base.put(specialist.id(), 110.0);
                base.put(incomplete.id(), 120.0);
            } else if (scenarioIndex == 1) base.put(incomplete.id(), 5.0);
            bundles.add(writeBundle(
                    "bundle-" + scenarioIndex + "-ref",
                    referenceId,
                    scenario,
                    scheduled,
                    base,
                    1,
                    anchors,
                    scenarioIndex * 100L));
            bundles.add(writeBundle(
                    "bundle-" + scenarioIndex + "-fast",
                    "fast-" + scenarioIndex,
                    scenario,
                    scheduled,
                    base,
                    2,
                    anchors,
                    scenarioIndex * 100L + 20));
            bundles.add(writeBundle(
                    "bundle-" + scenarioIndex + "-slow",
                    "slow-" + scenarioIndex,
                    scenario,
                    scheduled,
                    base,
                    .5,
                    anchors,
                    scenarioIndex * 100L + 40));
            scenarioIndex++;
        }
        AnchorCatalog catalog = AnchorCatalog.of(anchors);
        CalibrationPlan plan =
                new CalibrationPlan(catalog, new ReferenceRunCatalog(1, catalog.anchorSetId(), references));
        return new Corpus(bundles, scenarios, plan, robust, specialist, incomplete);
    }

    private Path writeBundle(
            String directory,
            String runId,
            SourceScenario scenario,
            List<PolicyVector> policies,
            Map<PolicyId, Double> base,
            double scale,
            List<PolicyVector> anchors,
            long startOffset) {
        Map<PolicyId, double[]> throughputs = new HashMap<>();
        base.forEach((id, value) -> throughputs.put(id, new double[] {value * scale, value * scale, value * scale}));
        return writeSuccessBundle(
                temporary.resolve(directory),
                run(runId, scenario, 3, EvidenceOrigin.NATIVE, START.plusSeconds(startOffset)),
                policies,
                throughputs,
                new HashSet<>(anchors.stream().map(PolicyVector::id).toList()));
    }

    private void writeWorkspaceCalibration(Path workspace, CalibrationPlan plan) throws Exception {
        Files.createDirectories(workspace);
        CalibrationPlanCsv.write(workspace.resolve("calibration-plan"), plan);
        Files.createDirectories(workspace.resolve("evidence"));
    }

    private void populateReferenceEvidence(Path workspace, List<Path> bundles, CalibrationPlan plan) throws Exception {
        for (String runId : plan.references().referenceRunIds().values()) {
            copyBundle(
                    findBundleByRunId(bundles, runId),
                    workspace.resolve("evidence").resolve(runId));
        }
    }

    private Path findBundleByRunId(List<Path> bundles, String runId) {
        return bundles.stream()
                .filter(bundle -> ObservationBundleReader.read(bundle)
                        .run()
                        .descriptor()
                        .benchmarkRunId()
                        .equals(runId))
                .findFirst()
                .orElseThrow();
    }

    private void writeReferenceBundle(Path workspace, String runId, SourceScenario scenario, List<PolicyVector> anchors)
            throws Exception {
        Map<PolicyId, double[]> throughputs = new HashMap<>();
        for (int index = 0; index < anchors.size(); index++) {
            double value = 100 + index;
            throughputs.put(anchors.get(index).id(), new double[] {value, value, value});
        }
        writeSuccessBundle(
                workspace.resolve("evidence").resolve(runId),
                run(runId, scenario, 3, EvidenceOrigin.NATIVE, START),
                anchors,
                throughputs,
                new HashSet<>(anchors.stream().map(PolicyVector::id).toList()));
    }

    private CalibrationPlan subsetPlan(CalibrationPlan plan, List<SourceScenario> scenarios) {
        SortedMap<SourceScenario, String> references = new TreeMap<>();
        for (SourceScenario scenario : scenarios) {
            references.put(scenario, plan.references().referenceRunIds().get(scenario));
        }
        return new CalibrationPlan(
                plan.anchors(), new ReferenceRunCatalog(1, plan.anchors().anchorSetId(), references));
    }

    private static Path copyBundle(Path source, Path target) throws Exception {
        Files.createDirectory(target);
        try (var files = Files.list(source)) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        return target;
    }

    private record Corpus(
            List<Path> bundles,
            SortedSet<SourceScenario> scenarios,
            CalibrationPlan plan,
            PolicyVector robust,
            PolicyVector specialist,
            PolicyVector incomplete) {}
}
