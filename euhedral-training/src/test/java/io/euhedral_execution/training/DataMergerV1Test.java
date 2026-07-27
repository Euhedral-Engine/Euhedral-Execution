package io.euhedral_execution.training;

import static io.euhedral_execution.training.fixtures.SyntheticObservations.*;
import static org.assertj.core.api.Assertions.*;

import io.euhedral_execution.training.data.*;
import io.euhedral_execution.training.merge.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataMergerV1Test {
    @TempDir Path temporary;

    @Test
    void writesDeterministicHierarchicalArtifactsAndRanksRobustPolicyFirst() throws Exception {
        Corpus corpus = corpus();
        Path first = temporary.resolve("merge-first");
        Path second = temporary.resolve("merge-second");
        DataMerger.MergeArtifacts artifacts = DataMerger.mergeV1(request(corpus.bundles, first,
                corpus.plan, corpus.scenarios));
        List<Path> reversed = new ArrayList<>(corpus.bundles);
        Collections.reverse(reversed);
        DataMerger.mergeV1(request(reversed, second, corpus.plan, corpus.scenarios));

        List<String> names = List.of("fixed-anchors.csv", "reference-runs.csv",
                "calibration-report.csv", "scenario-results.csv", "robust-ranking.csv",
                "coverage-report.csv", "robust-leaders.vectors.csv",
                "incomplete-policies.vectors.csv");
        for (String name : names) {
            assertThat(first.resolve(name)).isRegularFile();
            assertThat(Files.readString(first.resolve(name))).startsWith("schema_version,");
            assertThat(Files.readAllBytes(first.resolve(name)))
                    .isEqualTo(Files.readAllBytes(second.resolve(name)));
        }
        Map<String, String> headers = Map.of(
                "calibration-report.csv", "schema_version,calibration_acceptance,scenario_id,benchmark_run_id,reference_run_id,anchor_set_id,fixed_anchor_count,shared_anchor_count,delta_log,scale_factor,weighted_median_absolute_residual,status,reason",
                "scenario-results.csv", "schema_version,scenario_id,environment_id,source_count,available_physical_core_count,source_ratio_numerator,source_ratio_denominator,policy_id,status,total_run_count,accepted_run_count,weak_run_count,uncalibrated_run_count,successful_repetition_count,planned_repetition_count,throughput_p25,throughput_median,throughput_p75,throughput_iqr,median_within_run_relative_iqr,mean_timeout_rate,mean_failure_rate,mean_non_success_rate,bootstrap_median_ci_low,bootstrap_median_ci_high,quality",
                "robust-ranking.csv", "schema_version,published_rank,policy_id,eligible,required_scenario_count,observed_required_scenario_count,valid_required_scenario_count,coverage_fraction,worst_quality,quality_p25,geometric_mean_quality,cross_scenario_quality_mad,median_relative_iqr,mean_non_success_rate,mean_timeout_rate,missing_scenarios",
                "coverage-report.csv", "schema_version,policy_id,eligible,required_scenario_count,observed_required_scenario_count,valid_required_scenario_count,measured_scenarios,missing_scenarios,rejected_scenarios");
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
        assertThat(calibrations).contains(",2.0,0.0,CALIBRATED,STRONG")
                .contains(",0.5,0.0,CALIBRATED,STRONG");
        String coverage = Files.readString(artifacts.coverageReport());
        String specialistCoverage = coverage.lines().filter(line ->
                line.startsWith("1," + corpus.specialist.id().canonical() + ","))
                .findFirst().orElseThrow();
        String expectedMissing = corpus.scenarios.stream().skip(1)
                .map(SourceScenario::canonical).reduce((left, right) -> left + ";" + right)
                .orElseThrow();
        assertThat(specialistCoverage).contains("," + expectedMissing + ",");
        assertThat(Files.readString(artifacts.robustLeaderVectors()))
                .startsWith("schema_version,robust_rank,policy_id,weight_00_bits");
        assertThat(Files.readString(artifacts.incompleteVectors()))
                .startsWith("schema_version,valid_required_scenario_count");
    }

    @Test
    void invalidDuplicateCorpusPublishesNothing() throws Exception {
        Corpus corpus = corpus();
        List<Path> duplicate = new ArrayList<>(corpus.bundles);
        duplicate.add(corpus.bundles.getFirst());
        Path output = temporary.resolve("must-not-exist");
        assertThatIllegalArgumentException().isThrownBy(
                () -> DataMerger.mergeV1(request(duplicate, output, corpus.plan, corpus.scenarios)));
        assertThat(output).doesNotExist();
    }

    @Test
    void incompatibleSchemaAndPolicyMismatchPublishNothing() throws Exception {
        Corpus corpus = corpus();
        for (String kind : List.of("schema", "policy")) {
            Path changed = copyBundle(corpus.bundles.getFirst(),
                    temporary.resolve("tampered-" + kind));
            Path file = changed.resolve(kind.equals("schema") ? "observations.csv" : "policies.csv");
            String text = Files.readString(file);
            text = kind.equals("schema") ? text.replaceFirst("(?m)^1,", "2,")
                    : text.replaceFirst("p1-[0-9a-f]{16}", "p1-0000000000000000");
            Files.writeString(file, text);
            List<Path> bundles = new ArrayList<>(corpus.bundles);
            bundles.set(0, changed);
            Path output = temporary.resolve("invalid-" + kind);
            assertThatRuntimeException().isThrownBy(
                    () -> DataMerger.mergeV1(request(bundles, output,
                            corpus.plan, corpus.scenarios)));
            assertThat(output).doesNotExist();
        }
    }

    private DataMerger.MergeRequest request(List<Path> bundles, Path output,
            CalibrationPlan plan, SortedSet<SourceScenario> scenarios) {
        return new DataMerger.MergeRequest(bundles, scenarios, plan, output,
                CalibrationConfig.defaults(), AggregationConfig.defaults());
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
                {100, 10, 100, 100}, {100, 100, 10, 100}, {100, 100, 100, 10},
                {10, 100, 100, 100}, {20, 20, 100, 100}
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
            bundles.add(writeBundle("bundle-" + scenarioIndex + "-ref", referenceId,
                    scenario, scheduled, base, 1, anchors, scenarioIndex * 100L));
            bundles.add(writeBundle("bundle-" + scenarioIndex + "-fast", "fast-" + scenarioIndex,
                    scenario, scheduled, base, 2, anchors, scenarioIndex * 100L + 20));
            bundles.add(writeBundle("bundle-" + scenarioIndex + "-slow", "slow-" + scenarioIndex,
                    scenario, scheduled, base, .5, anchors, scenarioIndex * 100L + 40));
            scenarioIndex++;
        }
        AnchorCatalog catalog = AnchorCatalog.of(anchors);
        CalibrationPlan plan = new CalibrationPlan(catalog,
                new ReferenceRunCatalog(1, catalog.anchorSetId(), references));
        return new Corpus(bundles, scenarios, plan, robust, specialist, incomplete);
    }

    private Path writeBundle(String directory, String runId, SourceScenario scenario,
            List<PolicyVector> policies, Map<PolicyId, Double> base, double scale,
            List<PolicyVector> anchors, long startOffset) {
        Map<PolicyId, double[]> throughputs = new HashMap<>();
        base.forEach((id, value) -> throughputs.put(id,
                new double[]{value * scale, value * scale, value * scale}));
        return writeSuccessBundle(temporary.resolve(directory), run(runId, scenario, 3,
                EvidenceOrigin.NATIVE, START.plusSeconds(startOffset)), policies, throughputs,
                new HashSet<>(anchors.stream().map(PolicyVector::id).toList()));
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

    private record Corpus(List<Path> bundles, SortedSet<SourceScenario> scenarios,
            CalibrationPlan plan, PolicyVector robust, PolicyVector specialist,
            PolicyVector incomplete) {
    }
}
