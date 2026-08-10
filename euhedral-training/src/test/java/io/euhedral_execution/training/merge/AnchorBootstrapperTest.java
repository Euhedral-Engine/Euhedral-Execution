package io.euhedral_execution.training.merge;

import static io.euhedral_execution.training.fixtures.SyntheticObservations.CalibrationRole;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.START;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.aggregate;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.BenchmarkRunDescriptor;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.config.AnchorSelectionConfig;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.merge.data.CalibrationPlanCsv;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnchorBootstrapperTest {
    private final SourceScenario one = SourceScenario.of("host-a", 1, 32);
    private final SourceScenario two = SourceScenario.of("host-a", 4, 32);
    private final List<PolicyVector> policies =
            List.of(policy(1), policy(2), policy(3), policy(4), policy(5), policy(6));
    @TempDir
    java.nio.file.Path temporary;

    @Test
    void computesSettledTargetCount() {
        assertThat(AnchorSelectionConfig.defaults().targetCount(6)).isEqualTo(5);
        assertThat(AnchorSelectionConfig.defaults().targetCount(300)).isEqualTo(6);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AnchorSelectionConfig.defaults().targetCount(5));
    }

    @Test
    void selectsEarliestReferencesAndStableStratifiedAnchorsDeterministically() throws Exception {
        List<RunAggregate> rows = corpus();
        CalibrationPlan plan = bootstrap(rows, Map.of());
        assertThat(plan.references().referenceRunIds())
                .containsEntry(one, "one-a")
                .containsEntry(two, "two-a");
        assertThat(plan.anchors().fixedAnchors())
                .hasSize(5)
                .isSortedAccordingTo(Comparator.comparing(PolicyVector::id));
        assertThat(plan.anchors().fixedAnchors()).doesNotContain(policies.getLast());
        List<RunAggregate> shuffled = new ArrayList<>(rows);
        Collections.shuffle(shuffled, new Random(123));
        CalibrationPlan shuffledPlan = bootstrap(shuffled, Map.of());
        assertThat(shuffledPlan).isEqualTo(plan);
        java.nio.file.Path forwardDirectory = temporary.resolve("forward");
        java.nio.file.Path shuffledDirectory = temporary.resolve("shuffled");
        CalibrationPlanCsv.write(forwardDirectory, plan);
        CalibrationPlanCsv.write(shuffledDirectory, shuffledPlan);
        for (String file : List.of("fixed-anchors.csv", "reference-runs.csv")) {
            assertThat(java.nio.file.Files.readAllBytes(forwardDirectory.resolve(file)))
                    .isEqualTo(java.nio.file.Files.readAllBytes(shuffledDirectory.resolve(file)));
        }
    }

    @Test
    void persistedPlanCannotBeMutated() throws Exception {
        CalibrationPlan plan = bootstrap(corpus(), Map.of());
        CalibrationPlanCsv.write(temporary, plan);
        assertThatIllegalArgumentException().isThrownBy(() -> CalibrationPlanCsv.write(temporary, plan));
        assertThat(CalibrationPlanCsv.read(temporary, List.of(one, two))).isEqualTo(plan);
    }

    @Test
    void honorsExplicitReferenceOverride() {
        CalibrationPlan plan = bootstrap(corpus(), Map.of(one, "one-z"));
        assertThat(plan.references().referenceRunIds().get(one)).isEqualTo("one-z");
    }

    @Test
    void selectsDocumentedStratumMidpoints() {
        AnchorSelectionConfig permissive = new AnchorSelectionConfig(0.02, 5, 0.10, 1.0, false);
        CalibrationPlan plan = AnchorBootstrapper.bootstrap(
                corpus(), new TreeSet<>(Set.of(one, two)), 6, Map.of(), permissive, AggregationConfig.defaults());
        assertThat(plan.anchors().fixedAnchors())
                .containsExactlyInAnyOrder(
                        policies.get(0), policies.get(1), policies.get(3), policies.get(4), policies.get(5));
    }

    @Test
    void coldStartsFromGeneratedBootstrapWhenValidIntersectionIsBelowTarget() {
        CalibrationPlan plan = AnchorBootstrapper.bootstrap(
                corpus(),
                new TreeSet<>(Set.of(one, two)),
                1024,
                Map.of(),
                AnchorSelectionConfig.defaults(),
                AggregationConfig.defaults());

        assertThat(plan.references().referenceRunIds())
                .containsEntry(one, "one-a")
                .containsEntry(two, "two-a");
        assertThat(plan.anchors().fixedAnchors())
                .hasSize(5)
                .containsExactlyInAnyOrder(
                        policies.get(0), policies.get(1), policies.get(2), policies.get(3), policies.get(4));
    }

    @Test
    void rejectsShortIntersectionAndImportedDefaultReference() {
        List<RunAggregate> shortRows = new ArrayList<>(corpus());
        shortRows.removeIf(row -> row.run().descriptor().scenario().equals(one)
                        && row.policy().equals(policies.get(5))
                || row.run().descriptor().scenario().equals(two)
                        && (row.policy().equals(policies.get(0)) || row.policy().equals(policies.get(5))));
        shortRows.add(aggregate(
                policies.get(5),
                "two-a",
                two,
                new double[] {1188, 1200, 1212},
                3,
                0,
                0,
                0,
                CalibrationRole.CANDIDATE,
                START));
        shortRows.add(aggregate(
                policies.get(5),
                "two-z",
                two,
                new double[] {1247.4, 1260, 1272.6},
                3,
                0,
                0,
                0,
                CalibrationRole.CANDIDATE,
                START.plusSeconds(10)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> bootstrap(shortRows, Map.of()))
                .withMessageContaining("intersection");

        List<RunAggregate> imported = corpus().stream().map(this::asImported).toList();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> bootstrap(imported, Map.of()))
                .withMessageContaining("No valid bootstrap evidence exists");
    }

    private CalibrationPlan bootstrap(List<RunAggregate> rows, Map<SourceScenario, String> overrides) {
        return AnchorBootstrapper.bootstrap(
                rows,
                new TreeSet<>(Set.of(one, two)),
                6,
                overrides,
                AnchorSelectionConfig.defaults(),
                AggregationConfig.defaults());
    }

    private List<RunAggregate> corpus() {
        List<RunAggregate> rows = new ArrayList<>();
        addRun(rows, "one-a", one, START, 1);
        addRun(rows, "one-z", one, START, 1.1);
        addRun(rows, "two-a", two, START, 2);
        addRun(rows, "two-z", two, START.plusSeconds(10), 2.1);
        return rows;
    }

    private void addRun(
            List<RunAggregate> rows,
            String runId,
            SourceScenario scenario,
            java.time.Instant start,
            double multiplier) {
        for (int i = 0; i < policies.size(); i++) {
            double value = (i + 1) * 100 * multiplier;
            double[] repetitions = i == policies.size() - 1
                    ? new double[] {value * .5, value, value * 1.5}
                    : new double[] {value * .99, value, value * 1.01};
            rows.add(aggregate(
                    policies.get(i), runId, scenario, repetitions, 3, 0, 0, 0, CalibrationRole.CANDIDATE, start));
        }
    }

    private RunAggregate asImported(RunAggregate row) {
        BenchmarkRunDescriptor old = row.run().descriptor();
        BenchmarkRunDescriptor descriptor = new BenchmarkRunDescriptor(
                1,
                old.benchmarkRunId(),
                old.closedLoopIteration(),
                old.candidateCohortId(),
                old.scenario(),
                "imported",
                false,
                EvidenceOrigin.IMPORTED,
                old.startedAt(),
                old.parameters());
        return new RunAggregate(
                row.policy(),
                new BenchmarkRunContext(descriptor, row.run().completedAt()),
                row.roles(),
                row.plannedRepetitionCount(),
                row.successfulRepetitionCount(),
                row.timeoutCount(),
                row.failedCount(),
                row.skippedCount(),
                row.successRate(),
                row.timeoutRate(),
                row.failureRate(),
                row.nonSuccessRate(),
                row.status(),
                row.rawP25(),
                row.rawMedian(),
                row.rawP75(),
                row.rawIqr(),
                row.rawLogIqr());
    }
}
