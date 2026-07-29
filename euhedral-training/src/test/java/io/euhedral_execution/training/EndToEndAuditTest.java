package io.euhedral_execution.training;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import io.euhedral_execution.training.checkpoint.CheckpointSnapshotCodec;
import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.checkpoint.enums.PendingRunStatus;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.merge.enums.CalibrationStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EndToEndAuditTest {
    @TempDir
    Path temporary;

    @Test
    void importsBootstrapsInterruptsResumesAndPackagesDeterministically()
            throws Exception {
        AuditFixtures.Experiment experiment = AuditFixtures.execute(temporary);
        assertThat(experiment.controlBootstrapA().awaitingScenarios())
                .containsExactly(SourceScenario.of("audit-b", 1, 4),
                        SourceScenario.of("audit-b", 4, 4));
        assertThat(experiment.resumedBootstrapA().awaitingScenarios())
                .containsExactlyElementsOf(
                        experiment.controlBootstrapA().awaitingScenarios());
        assertThat(experiment.controlReady().nextIteration()).isEqualTo(2);
        assertThat(experiment.resumedReady().nextIteration()).isEqualTo(2);
        assertThat(experiment.controlComplete().stage())
                .isEqualTo(CheckpointStage.RUN_COMPLETE);
        assertThat(experiment.resumedComplete().stage())
                .isEqualTo(CheckpointStage.RUN_COMPLETE);

        var interrupted = CheckpointSnapshotCodec.loadRevision(
                experiment.resumedWorkspace(),
                AuditFixtures.revision(experiment.interrupted().latestCheckpoint()))
                .checkpoint();
        assertThat(interrupted.stage()).isEqualTo(CheckpointStage.BENCHMARKING);
        assertThat(interrupted.evidence()).hasSize(5);
        assertThat(interrupted.pendingRuns()).extracting(row -> row.status())
                .containsExactly(PendingRunStatus.COMPLETE, PendingRunStatus.PENDING);

        assertEquivalentFinalState(experiment);
        assertBootstrapOracle(experiment);
        assertCalibrationOracle(experiment);
        assertFinalRankingOracle(experiment);
        assertFailedObservationOracle(experiment);
        assertPackageCounts(experiment);
    }

    private static void assertEquivalentFinalState(
            AuditFixtures.Experiment experiment) throws Exception {
        for (int iteration = 1; iteration <= 2; iteration++) {
            Path relative = Path.of(
                    "iterations/iteration-%06d/schedule".formatted(iteration));
            assertThat(ArtifactFingerprint.sha256(
                    experiment.controlWorkspace().resolve(relative)))
                    .isEqualTo(ArtifactFingerprint.sha256(
                            experiment.resumedWorkspace().resolve(relative)));
        }
        for (String name : List.of("calibration-report.csv", "scenario-results.csv",
                "robust-ranking.csv", "coverage-report.csv",
                "robust-leaders.vectors.csv", "incomplete-policies.vectors.csv")) {
            assertThat(Files.mismatch(
                    experiment.controlWorkspace().resolve("merges/merge-000002")
                            .resolve(name),
                    experiment.resumedWorkspace().resolve("merges/merge-000002")
                            .resolve(name))).isEqualTo(-1);
        }
        assertThat(ArtifactFingerprint.sha256(
                experiment.controlComplete().latestCheckpoint()))
                .isEqualTo(ArtifactFingerprint.sha256(
                        experiment.resumedComplete().latestCheckpoint()));
        assertThat(ArtifactFingerprint.sha256(
                experiment.controlPackage().directory()))
                .isEqualTo(ArtifactFingerprint.sha256(
                        experiment.resumedPackage().directory()))
                .isEqualTo(ArtifactFingerprint.sha256(
                        experiment.reproducedPackage().directory()));
    }

    private static void assertBootstrapOracle(
            AuditFixtures.Experiment experiment) throws Exception {
        List<List<String>> ranking = CanonicalCsv.read(
                experiment.controlWorkspace().resolve(
                        "merges/merge-000000/robust-ranking.csv"));
        Map<PolicyId, AuditFixtures.PolicyMeaning> meanings =
                experiment.corpus().meanings();
        List<String> expected = new ArrayList<>(List.of("R", "A4", "A3", "A2",
                "A1", "A0"));
        meanings.values().stream().filter(item -> item.symbol().startsWith("S"))
                .sorted(Comparator.comparing(item -> item.policy().id()))
                .map(AuditFixtures.PolicyMeaning::symbol).forEach(expected::add);
        assertThat(ranking.subList(1, ranking.size()).stream()
                .map(row -> meanings.get(PolicyId.parse(row.get(2))).symbol()).toList())
                .containsExactlyElementsOf(expected);
        List<String> robust = ranking.stream().skip(1)
                .filter(row -> meanings.get(PolicyId.parse(row.get(2))).symbol().equals("R"))
                .findFirst().orElseThrow();
        for (int index : List.of(8, 9, 10)) {
            assertThat(Double.doubleToRawLongBits(Double.parseDouble(robust.get(index))))
                    .isEqualTo(Double.doubleToRawLongBits(8.0 / 9.0));
        }
        assertThat(Double.parseDouble(robust.get(11))).isZero();
    }

    private static void assertCalibrationOracle(
            AuditFixtures.Experiment experiment) throws Exception {
        List<List<String>> rows = CanonicalCsv.read(
                experiment.resumedWorkspace().resolve(
                        "merges/merge-000002/calibration-report.csv"));
        assertThat(rows).hasSize(9);
        long references = 0;
        long calibrated = 0;
        for (List<String> row : rows.subList(1, rows.size())) {
            CalibrationStatus status = CalibrationStatus.valueOf(row.get(11));
            if (status == CalibrationStatus.REFERENCE) {
                references++;
                exact(row.get(8), 0);
                exact(row.get(9), 1);
                exact(row.get(10), 0);
            } else {
                calibrated++;
                SourceScenario scenario = SourceScenario.parse(row.get(2));
                double expectedScale = scenario.environmentId().equals("audit-b")
                        ? 0.5 : 2.0;
                exact(row.get(8), StrictMath.log(expectedScale));
                exact(row.get(9), expectedScale);
                exact(row.get(10), 0);
                assertThat(status).isEqualTo(CalibrationStatus.CALIBRATED);
            }
        }
        assertThat(references).isEqualTo(4);
        assertThat(calibrated).isEqualTo(4);
    }

    private static void assertFinalRankingOracle(
            AuditFixtures.Experiment experiment) throws Exception {
        Path merge = experiment.resumedWorkspace().resolve("merges/merge-000002");
        List<List<String>> ranking = CanonicalCsv.read(
                merge.resolve("robust-ranking.csv"));
        String robustId = experiment.corpus().bySymbol("R").policy().id().canonical();
        assertThat(ranking.get(1).get(1)).isEqualTo("1");
        assertThat(ranking.get(1).get(2)).isEqualTo(robustId);
        assertThat(ranking.stream().skip(1)
                .filter(row -> row.get(2).equals(
                        experiment.failedPolicyId().canonical()))
                .findFirst().orElseThrow().get(1)).isEmpty();

        List<List<String>> scenarios = CanonicalCsv.read(
                merge.resolve("scenario-results.csv"));
        Map<SourceScenario, List<List<String>>> byScenario = new HashMap<>();
        scenarios.stream().skip(1).forEach(row -> byScenario.computeIfAbsent(
                SourceScenario.parse(row.get(1)), ignored -> new ArrayList<>()).add(row));
        ArrayList<Double> robustQualities = new ArrayList<>();
        for (SourceScenario scenario : experiment.corpus().scenarios()) {
            List<List<String>> valid = byScenario.get(scenario).stream()
                    .filter(row -> row.get(8).startsWith("VALID_")).toList();
            List<String> robust = valid.stream().filter(row -> row.get(7)
                    .equals(robustId)).findFirst().orElseThrow();
            double robustThroughput = Double.parseDouble(robust.get(16));
            assertThat(robustThroughput).isEqualTo(90);
            assertThat(valid.stream().filter(row ->
                    Double.parseDouble(row.get(16)) > robustThroughput).count())
                    .isOne();
            double expected = independentMidrank(valid, robustThroughput);
            exact(robust.get(25), expected);
            robustQualities.add(expected);
        }
        List<String> robustSummary = ranking.get(1);
        exact(robustSummary.get(8), robustQualities.stream()
                .mapToDouble(Double::doubleValue).min().orElseThrow());
        exact(robustSummary.get(9), type7(robustQualities, 0.25));
        exact(robustSummary.get(10), StrictMath.exp(robustQualities.stream()
                .mapToDouble(StrictMath::log).average().orElseThrow()));
        double median = type7(robustQualities, 0.5);
        exact(robustSummary.get(11), type7(robustQualities.stream()
                .map(value -> StrictMath.abs(value - median)).toList(), 0.5));

        for (String symbol : List.of("S0", "S1", "S2", "S3")) {
            String specialist = experiment.corpus().bySymbol(symbol).policy().id().canonical();
            List<String> row = ranking.stream().skip(1)
                    .filter(item -> item.get(2).equals(specialist)).findFirst().orElseThrow();
            assertThat(row.get(1)).isNotEmpty();
            assertThat(Integer.parseInt(row.get(1))).isGreaterThan(1);
        }

        List<List<String>> coverage = CanonicalCsv.read(
                merge.resolve("coverage-report.csv"));
        List<String> failed = coverage.stream().skip(1).filter(row ->
                row.get(1).equals(experiment.failedPolicyId().canonical()))
                .findFirst().orElseThrow();
        assertThat(failed.get(2)).isEqualTo("false");
        assertThat(failed.get(7)).isEmpty();
        assertThat(failed.get(8)).isEqualTo(
                SourceScenario.of("audit-b", 1, 4).canonical());

        Set<String> incomplete = ranking.stream().skip(1)
                .filter(row -> row.get(1).isEmpty()).map(row -> row.get(2))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        assertThat(incomplete).contains(experiment.failedPolicyId().canonical());
        for (int iteration = 1; iteration <= 2; iteration++) {
            List<List<String>> scheduled = CanonicalCsv.read(
                    experiment.resumedWorkspace().resolve(
                            "iterations/iteration-%06d/schedule/policies.csv"
                                    .formatted(iteration)));
            assertThat(scheduled.stream().skip(1)
                    .filter(row -> incomplete.contains(row.get(4))))
                    .allSatisfy(row -> assertThat(row.get(5))
                            .doesNotContain("LEADER_REVALIDATION"));
        }
    }

    private static void assertFailedObservationOracle(
            AuditFixtures.Experiment experiment) throws Exception {
        List<Path> failedBundles = new ArrayList<>();
        try (var paths = Files.walk(experiment.resumedWorkspace().resolve("evidence"))) {
            for (Path observations : paths.filter(path ->
                    path.getFileName().toString().equals("observations.csv")).toList()) {
                List<List<String>> rows = CanonicalCsv.read(observations).stream().skip(1)
                        .filter(row -> row.get(2).equals(
                                experiment.failedPolicyId().canonical()))
                        .toList();
                if (rows.stream().anyMatch(row -> row.get(4).equals("TIMEOUT"))) {
                    failedBundles.add(observations.getParent());
                }
            }
        }
        assertThat(failedBundles).hasSize(1);
        Path failedBundle = failedBundles.getFirst();
        List<String> run = CanonicalCsv.read(failedBundle.resolve("run.csv")).get(1);
        assertThat(run.get(2)).isEqualTo("1");
        assertThat(run.get(4)).isEqualTo(
                SourceScenario.of("audit-b", 1, 4).canonical());
        List<List<String>> failed = CanonicalCsv.read(
                failedBundle.resolve("observations.csv")).stream().skip(1)
                .filter(row -> row.get(2).equals(
                        experiment.failedPolicyId().canonical()))
                .toList();
        assertThat(failed).extracting(row -> row.get(4))
                .containsExactly("TIMEOUT", "SKIPPED", "SKIPPED");
        assertThat(failed).extracting(row -> row.get(10))
                .containsExactly("0.0", "", "");
        assertThat(failed).extracting(row -> row.get(11))
                .containsExactly("AUDIT_TIMEOUT", "AFTER_TIMEOUT", "AFTER_TIMEOUT");
        assertThat(failed).extracting(row -> row.get(4))
                .doesNotContain("SUCCESS");
    }

    private static double independentMidrank(List<List<String>> valid,
            double target) {
        List<Double> ordered = valid.stream()
                .map(row -> Double.parseDouble(row.get(16))).sorted().toList();
        int first = ordered.indexOf(target);
        int last = ordered.lastIndexOf(target);
        double rank = (first + 1 + last + 1) / 2.0;
        return ordered.size() == 1 ? 1 : (rank - 1) / (ordered.size() - 1);
    }

    private static double type7(List<Double> values, double probability) {
        List<Double> sorted = values.stream().sorted().toList();
        if (sorted.size() == 1) {
            return sorted.getFirst();
        }
        double h = (sorted.size() - 1) * probability;
        int lower = (int) StrictMath.floor(h);
        double fraction = h - lower;
        return sorted.get(lower) + fraction
                * (sorted.get(Math.min(lower + 1, sorted.size() - 1))
                - sorted.get(lower));
    }

    private static void assertPackageCounts(
            AuditFixtures.Experiment experiment) throws Exception {
        assertThat(fileCount(experiment.interruptedPackage().directory())).isEqualTo(58);
        assertThat(fileCount(experiment.resumedPackage().directory())).isEqualTo(70);
        try (var paths = Files.walk(experiment.resumedPackage().directory())) {
            assertThat(paths.filter(path -> path.getFileName().toString()
                    .equals("COMPLETE")).toList()).allSatisfy(path -> {
                        try {
                            assertThat(ArtifactFingerprint.sha256(path))
                                    .isEqualTo(AuditFixtures.EMPTY_SHA256);
                        } catch (Exception error) {
                            throw new AssertionError(error);
                        }
                    });
        }
    }

    private static long fileCount(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static void exact(String actual, double expected) {
        assertThat(Double.doubleToRawLongBits(Double.parseDouble(actual)))
                .isEqualTo(Double.doubleToRawLongBits(expected));
    }
}
