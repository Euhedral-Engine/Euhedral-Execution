package io.euhedral_execution.training.merge;

import static io.euhedral_execution.training.fixtures.SyntheticObservations.CalibrationRole;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.START;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.aggregate;
import static io.euhedral_execution.training.fixtures.SyntheticObservations.policy;
import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.config.CalibrationConfig;
import io.euhedral_execution.training.merge.data.AnchorCatalog;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.merge.data.MergeRecords;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregate;
import io.euhedral_execution.training.merge.data.ReferenceRunCatalog;
import io.euhedral_execution.training.merge.enums.CalibrationStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class RunCalibratorTest {
    private final SourceScenario scenario = SourceScenario.of("host-a", 4, 32);
    private final List<PolicyVector> anchors = List.of(policy(1), policy(2), policy(3), policy(4), policy(5));
    private final double[] referenceValues = {100, 200, 400, 800, 1600};

    private static double[] repeats(double value) {
        return new double[] {value, value, value, value, value};
    }

    @Test
    void removesGlobalTwoTimesScaleAndIgnoresCandidateCohort() {
        List<RunAggregate> first = runs("run-2", new double[] {2, 2, 2, 2, 2}, 1000, true);
        List<RunAggregate> second = runs("run-2", new double[] {2, 2, 2, 2, 2}, 100_000, true);
        var calibration = calibration(withReferences(first)).stream()
                .filter(row -> row.run().descriptor().benchmarkRunId().equals("run-2"))
                .findFirst()
                .orElseThrow();
        var changed = calibration(withReferences(second)).stream()
                .filter(row -> row.run().descriptor().benchmarkRunId().equals("run-2"))
                .findFirst()
                .orElseThrow();
        assertThat(calibration.deltaLog()).hasValue(StrictMath.log(2));
        assertThat(calibration.scaleFactor()).hasValue(2);
        assertThat(calibration.weightedMedianAbsoluteResidual()).hasValue(0);
        assertThat(calibration.status()).isEqualTo(CalibrationStatus.CALIBRATED);
        assertThat(1000 / calibration.scaleFactor().orElseThrow()).isEqualTo(500);
        assertThat(changed).isEqualTo(calibration);
    }

    @Test
    void stableMajorityAndWeightCapDefeatOutlier() {
        var equal = calibration(withReferences(runs("run-2", new double[] {2, 2, 2, 2, 8}, 1000, true)))
                .getLast();
        assertThat(equal.deltaLog()).hasValue(StrictMath.log(2));
        assertThat(equal.weightedMedianAbsoluteResidual()).hasValue(0);

        List<RunAggregate> values = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            double[] reference = new double[100];
            Arrays.fill(reference, referenceValues[i]);
            values.add(aggregate(
                    anchors.get(i), "reference", scenario, reference, 100, 0, 0, 0, CalibrationRole.CANDIDATE, START));
        }
        for (int i = 0; i < anchors.size() - 1; i++) {
            double[] stable = new double[51];
            Arrays.fill(stable, referenceValues[i] * 2);
            values.add(aggregate(
                    anchors.get(i),
                    "run-3",
                    scenario,
                    stable,
                    100,
                    49,
                    0,
                    0,
                    CalibrationRole.ANCHOR,
                    START.plusSeconds(10)));
        }
        double[] precise = new double[100];
        Arrays.fill(precise, 12_800);
        values.add(aggregate(
                anchors.getLast(),
                "run-3",
                scenario,
                precise,
                100,
                0,
                0,
                0,
                CalibrationRole.ANCHOR,
                START.plusSeconds(10)));
        var weighted = calibration(values).getLast();
        assertThat(weighted.cappedAnchorWeights().values())
                .allMatch(weight -> weight <= 0.25 || weight <= Math.nextUp(0.25));
        assertThat(weighted.cappedAnchorWeights().get(anchors.getLast().id())).isEqualTo(0.25);
        assertThat(weighted.deltaLog()).hasValue(StrictMath.log(2));
    }

    @Test
    void classifiesWeakInsufficientAndExcessiveResidual() {
        List<RunAggregate> four = withReferences(runs("run-four", new double[] {2, 2, 2, 2}, 0, false));
        assertThat(calibration(four).getLast().status()).isEqualTo(CalibrationStatus.WEAKLY_CALIBRATED);

        List<RunAggregate> two = withReferences(runs("run-two", new double[] {2, 2}, 0, false));
        assertThat(calibration(two).getLast()).satisfies(result -> {
            assertThat(result.status()).isEqualTo(CalibrationStatus.UNCALIBRATED);
            assertThat(result.reason()).isEqualTo("INSUFFICIENT_SHARED_ANCHORS");
            assertThat(result.scaleFactor()).isEmpty();
        });

        List<RunAggregate> excessiveRuns =
                withReferences(runs("run-wide", new double[] {1, 1.3, 1.6, 2, 2.5}, 0, false));
        PolicyVector candidate = policy(30);
        excessiveRuns.add(aggregate(
                candidate,
                "run-wide",
                scenario,
                repeats(500),
                5,
                0,
                0,
                0,
                CalibrationRole.CANDIDATE,
                START.plusSeconds(10)));
        List<MergeRecords.RunCalibration> excessiveCalibrations = calibration(excessiveRuns);
        var excessive = excessiveCalibrations.getLast();
        assertThat(excessive.status()).isEqualTo(CalibrationStatus.UNCALIBRATED);
        assertThat(excessive.reason()).isEqualTo("EXCESSIVE_RESIDUAL");
        var candidateScenario = HierarchicalAggregator.aggregateScenarios(
                        List.of(candidate),
                        excessiveRuns,
                        excessiveCalibrations,
                        new TreeSet<>(Set.of(scenario)),
                        AggregationConfig.defaults())
                .getFirst();
        assertThat(candidateScenario.status()).isEqualTo(MergeRecords.ScenarioResultStatus.NO_ACCEPTED_CALIBRATION);
    }

    @Test
    void requiresFixedAnchorRoleOutsideReferenceAndCalibratesDirectly() {
        List<RunAggregate> all = withReferences(runs("middle", new double[] {2, 2, 2, 2, 2}, 0, false));
        all.addAll(runs("final", new double[] {4, 4, 4, 4, 4}, 0, false));
        RunAggregate wrongRole = aggregate(
                anchors.getLast(),
                "final",
                scenario,
                repeats(6400),
                5,
                0,
                0,
                0,
                CalibrationRole.CANDIDATE,
                START.plusSeconds(10));
        all.removeIf(row -> row.run().descriptor().benchmarkRunId().equals("final")
                && row.policy().id().equals(anchors.getLast().id()));
        all.add(wrongRole);
        var finalRun = calibration(all).stream()
                .filter(row -> row.run().descriptor().benchmarkRunId().equals("final"))
                .findFirst()
                .orElseThrow();
        assertThat(finalRun.sharedAnchorCount()).isEqualTo(4);
        assertThat(finalRun.deltaLog()).hasValue(StrictMath.log(4));
        assertThat(finalRun.status()).isEqualTo(CalibrationStatus.WEAKLY_CALIBRATED);
    }

    @Test
    void preservesObservedNonRequiredScenarioWithoutInventingReference() {
        SourceScenario extra = SourceScenario.of("host-b", 8, 32);
        List<RunAggregate> rows = withReferences(List.of(aggregate(
                policy(40),
                "extra-run",
                extra,
                repeats(500),
                5,
                0,
                0,
                0,
                CalibrationRole.CANDIDATE,
                START.plusSeconds(20))));
        var extraCalibration = calibration(rows).stream()
                .filter(item -> item.run().descriptor().benchmarkRunId().equals("extra-run"))
                .findFirst()
                .orElseThrow();
        assertThat(extraCalibration.status()).isEqualTo(CalibrationStatus.UNCALIBRATED);
        assertThat(extraCalibration.referenceRunId()).isEmpty();
        assertThat(extraCalibration.reason()).isEqualTo("MISSING_SCENARIO_REFERENCE");
    }

    private List<RunAggregate> withReferences(List<RunAggregate> current) {
        List<RunAggregate> all = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++)
            all.add(aggregate(
                    anchors.get(i),
                    "reference",
                    scenario,
                    repeats(referenceValues[i]),
                    5,
                    0,
                    0,
                    0,
                    CalibrationRole.CANDIDATE,
                    START));
        all.addAll(current);
        return all;
    }

    private List<RunAggregate> runs(String runId, double[] ratios, double candidate, boolean addCandidate) {
        List<RunAggregate> rows = new ArrayList<>();
        for (int i = 0; i < ratios.length; i++)
            rows.add(aggregate(
                    anchors.get(i),
                    runId,
                    scenario,
                    repeats(referenceValues[i] * ratios[i]),
                    5,
                    0,
                    0,
                    0,
                    CalibrationRole.ANCHOR,
                    START.plusSeconds(10)));
        if (addCandidate)
            rows.add(aggregate(
                    policy(20),
                    runId,
                    scenario,
                    repeats(candidate),
                    5,
                    0,
                    0,
                    0,
                    CalibrationRole.CANDIDATE,
                    START.plusSeconds(10)));
        return rows;
    }

    private List<MergeRecords.RunCalibration> calibration(List<RunAggregate> rows) {
        AnchorCatalog catalog = AnchorCatalog.of(anchors);
        return RunCalibrator.calibrate(
                rows,
                new CalibrationPlan(
                        catalog,
                        new ReferenceRunCatalog(
                                1, catalog.anchorSetId(), new TreeMap<>(Map.of(scenario, "reference")))),
                CalibrationConfig.defaults());
    }
}
