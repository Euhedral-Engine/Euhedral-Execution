package io.euhedral_execution.training.merge;

import static io.euhedral_execution.training.fixtures.SyntheticObservations.CalibrationRole;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.START;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.aggregate;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.policy;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.data.enums.PolicyRole;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregate;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregateStatus;
import io.euhedral_execution.training.merge.data.MergeRecords.RunCalibration;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResult;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import io.euhedral_execution.training.merge.enums.CalibrationAcceptance;
import io.euhedral_execution.training.merge.enums.CalibrationStatus;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class HierarchicalAggregatorTest {
    private final SourceScenario scenario = SourceScenario.of("host-a", 4, 32);
    private final PolicyVector policy = policy(1);

    @Test
    void givesEachRunOneVoteRegardlessOfRepetitionCount() {
        double[] many = new double[101];
        for (int i = 0; i < many.length; i++) many[i] = 50 + i;
        RunAggregate runA = aggregate(policy, "run-a", scenario, many, 101, 0, 0, 0,
                CalibrationRole.CANDIDATE, START);
        RunAggregate runB = aggregate(policy, "run-b", scenario, new double[]{190, 200, 210},
                3, 0, 0, 0, CalibrationRole.CANDIDATE, START.plusSeconds(10));
        ScenarioResult result = merge(List.of(runA, runB), List.of(reference(runA),
                calibrated(runB, CalibrationStatus.CALIBRATED)), AggregationConfig.defaults(),
                new TreeSet<>(Set.of(scenario))).getFirst();
        assertThat(result.throughputMedian()).hasValue(150);
        assertThat(result.throughputP25()).hasValue(125);
        assertThat(result.throughputP75()).hasValue(175);
        assertThat(result.throughputMedian().orElseThrow()).isNotCloseTo(100.0,
                within(1.0));
    }

    @Test
    void timeoutsNeverEnterQuantilesAndRatesRemainVisible() {
        RunAggregate run = aggregate(policy, "run-a", scenario, new double[]{90, 100, 110},
                5, 2, 0, 0, CalibrationRole.CANDIDATE, START);
        ScenarioResult result = merge(List.of(run), List.of(reference(run)),
                AggregationConfig.defaults(), new TreeSet<>(Set.of(scenario))).getFirst();
        assertThat(result.throughputMedian()).hasValue(100);
        assertThat(result.throughputP25()).hasValue(100);
        assertThat(result.throughputP75()).hasValue(100);
        assertThat(run.rawP25()).hasValue(95);
        assertThat(run.rawP75()).hasValue(105);
        assertThat(result.meanTimeoutRate()).hasValue(0.4);
        assertThat(result.bootstrapMedianCiLow()).hasValue(100);
        assertThat(result.bootstrapMedianCiHigh()).hasValue(100);
    }

    @Test
    void rejectsInsufficientRunsAndReportsSkippedAsFailures() {
        RunAggregate insufficient = aggregate(policy, "run-a", scenario, new double[]{90, 100},
                5, 3, 0, 0, CalibrationRole.CANDIDATE, START);
        ScenarioResult result = merge(List.of(insufficient), List.of(reference(insufficient)),
                AggregationConfig.defaults(), new TreeSet<>(Set.of(scenario))).getFirst();
        assertThat(result.status()).isEqualTo(ScenarioResultStatus.NO_VALID_RUN);

        RunAggregate terminal = emptyAggregate("run-b", 1, 0, 4);
        assertThat(terminal.timeoutRate()).isEqualTo(0.2);
        assertThat(terminal.failureRate()).isEqualTo(0.8);
        assertThat(terminal.nonSuccessRate()).isEqualTo(1.0);
    }

    @Test
    void weakAcceptanceIsExplicitAndUncalibratedIsAlwaysExcluded() {
        RunAggregate weakRun = aggregate(policy, "run-w", scenario, new double[]{90, 100, 110},
                3, 0, 0, 0, CalibrationRole.CANDIDATE, START);
        RunCalibration weak = calibrated(weakRun, CalibrationStatus.WEAKLY_CALIBRATED);
        ScenarioResult excluded = merge(List.of(weakRun), List.of(weak),
                AggregationConfig.defaults(), new TreeSet<>(Set.of(scenario))).getFirst();
        assertThat(excluded.status()).isEqualTo(ScenarioResultStatus.NO_ACCEPTED_CALIBRATION);
        AggregationConfig includeWeak = new AggregationConfig(3, 0.5, 1000,
                AggregationConfig.defaults().bootstrapSeed(), CalibrationAcceptance.INCLUDE_WEAK);
        ScenarioResult included = merge(List.of(weakRun), List.of(weak), includeWeak,
                new TreeSet<>(Set.of(scenario))).getFirst();
        assertThat(included.status()).isEqualTo(ScenarioResultStatus.VALID_WEAK_OVERRIDE);

        RunCalibration uncalibrated = calibrated(weakRun, CalibrationStatus.UNCALIBRATED);
        assertThat(merge(List.of(weakRun), List.of(uncalibrated), includeWeak,
                new TreeSet<>(Set.of(scenario))).getFirst().status())
                .isEqualTo(ScenarioResultStatus.NO_ACCEPTED_CALIBRATION);
    }

    @Test
    void shuffledRunsProduceIdenticalBootstrapAndMissingRows() {
        RunAggregate first = aggregate(policy, "run-a", scenario, new double[]{90, 100, 110},
                3, 0, 0, 0, CalibrationRole.CANDIDATE, START);
        RunAggregate second = aggregate(policy, "run-b", scenario, new double[]{180, 200, 220},
                3, 0, 0, 0, CalibrationRole.CANDIDATE, START.plusSeconds(10));
        List<RunCalibration> calibrations = List.of(reference(first),
                calibrated(second, CalibrationStatus.CALIBRATED));
        SourceScenario missing = SourceScenario.of("host-a", 8, 32);
        SortedSet<SourceScenario> required = new TreeSet<>(Set.of(scenario, missing));
        List<ScenarioResult> forward = merge(List.of(first, second), calibrations,
                AggregationConfig.defaults(), required);
        List<ScenarioResult> reverse = merge(List.of(second, first), calibrations.reversed(),
                AggregationConfig.defaults(), required);
        assertThat(reverse).isEqualTo(forward);
        assertThat(forward).anyMatch(row -> row.scenario().equals(missing)
                && row.status() == ScenarioResultStatus.MISSING);
    }

    private List<ScenarioResult> merge(List<RunAggregate> runs,
            List<RunCalibration> calibrations, AggregationConfig config,
            SortedSet<SourceScenario> required) {
        return HierarchicalAggregator.aggregateScenarios(List.of(policy), runs, calibrations,
                required, config);
    }

    private RunCalibration reference(RunAggregate run) {
        return new RunCalibration(run.run(), run.run().descriptor().benchmarkRunId(),
                "a1-0000000000000001", 5, 5, OptionalDouble.of(0), OptionalDouble.of(1),
                OptionalDouble.of(0), CalibrationStatus.REFERENCE, "REFERENCE_RUN", new TreeMap<>());
    }

    private RunCalibration calibrated(RunAggregate run, CalibrationStatus status) {
        OptionalDouble value = status == CalibrationStatus.UNCALIBRATED
                ? OptionalDouble.empty() : OptionalDouble.of(1);
        return new RunCalibration(run.run(), "reference", "a1-0000000000000001", 5,
                status == CalibrationStatus.UNCALIBRATED ? 2 : 5,
                status == CalibrationStatus.UNCALIBRATED ? OptionalDouble.empty()
                        : OptionalDouble.of(0), value,
                status == CalibrationStatus.UNCALIBRATED ? OptionalDouble.empty()
                        : OptionalDouble.of(0),
                status, status.name(), new TreeMap<>());
    }

    private RunAggregate emptyAggregate(String runId, int timeout, int failed, int skipped) {
        SortedSet<PolicyRole> roles = new TreeSet<>(
                Comparator.comparing(Enum::name));
        roles.add(PolicyRole.EXPLORATION);
        return new RunAggregate(policy, new BenchmarkRunContext(
                run(runId, scenario, 5, EvidenceOrigin.NATIVE,
                        START), START.plusSeconds(6)), roles, 5, 0, timeout, failed, skipped,
                0, timeout / 5.0, (failed + skipped) / 5.0,
                (timeout + failed + skipped) / 5.0,
                RunAggregateStatus.INSUFFICIENT_SUCCESSES, OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalDouble.empty());
    }
}
