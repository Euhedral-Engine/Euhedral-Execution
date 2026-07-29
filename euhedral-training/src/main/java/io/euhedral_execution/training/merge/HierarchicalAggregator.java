package io.euhedral_execution.training.merge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregate;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregateStatus;
import io.euhedral_execution.training.merge.data.MergeRecords.RunCalibration;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResult;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import io.euhedral_execution.training.merge.enums.CalibrationAcceptance;
import io.euhedral_execution.training.merge.enums.CalibrationStatus;

public final class HierarchicalAggregator {

    public static List<ScenarioResult> aggregateScenarios(Collection<PolicyVector> policies,
            List<RunAggregate> runs, List<RunCalibration> calibrations,
            SortedSet<SourceScenario> requiredScenarios, AggregationConfig config) {
        Map<String, RunCalibration> calibrationByRun = new HashMap<>();
        calibrations.forEach(item -> {
            if (calibrationByRun.put(item.run().descriptor().benchmarkRunId(), item) != null) {
                throw new IllegalArgumentException("Duplicate run calibration");
            }
        });
        SortedSet<SourceScenario> scenarios = new TreeSet<>(requiredScenarios);
        runs.forEach(run -> scenarios.add(run.run().descriptor().scenario()));
        List<PolicyVector> sortedPolicies =
                policies.stream().sorted(Comparator.comparing(PolicyVector::id)).toList();
        List<ScenarioResult> results = new ArrayList<>();
        for (SourceScenario scenario : scenarios) {
            for (PolicyVector policy : sortedPolicies) {
                List<RunAggregate> matching = runs.stream()
                        .filter(run -> run.policy().id().equals(policy.id()) && run.run()
                                .descriptor().scenario().equals(scenario)).toList();
                results.add(aggregate(scenario, policy, matching, calibrationByRun, config));
            }
        }
        return List.copyOf(results);
    }

    private static ScenarioResult aggregate(SourceScenario scenario, PolicyVector policy,
            List<RunAggregate> matching, Map<String, RunCalibration> calibrations,
            AggregationConfig config) {
        if (matching.isEmpty()) {
            return empty(scenario, policy, ScenarioResultStatus.MISSING, 0, 0, 0);
        }
        int weak = 0, uncalibrated = 0;
        List<Accepted> accepted = new ArrayList<>();
        boolean hasValidRun = false;
        for (RunAggregate run : matching) {
            if (run.status() != RunAggregateStatus.VALID) {
                continue;
            }
            hasValidRun = true;
            RunCalibration calibration = calibrations.get(run.run().descriptor().benchmarkRunId());
            if (calibration == null) {
                throw new IllegalArgumentException("Missing calibration");
            }
            if (calibration.status() == CalibrationStatus.WEAKLY_CALIBRATED) {
                weak++;
            }
            if (calibration.status() == CalibrationStatus.UNCALIBRATED) {
                uncalibrated++;
            }
            boolean acceptedStatus = calibration.status() == CalibrationStatus.REFERENCE
                    || calibration.status() == CalibrationStatus.CALIBRATED
                    || calibration.status() == CalibrationStatus.WEAKLY_CALIBRATED
                    && config.calibrationAcceptance() == CalibrationAcceptance.INCLUDE_WEAK;
            if (acceptedStatus) {
                double scale = calibration.scaleFactor().orElseThrow();
                double median = run.rawMedian().getAsDouble() / scale;
                double p25 = run.rawP25().getAsDouble() / scale;
                double p75 = run.rawP75().getAsDouble() / scale;
                if (!Double.isFinite(scale) || scale <= 0 || !Double.isFinite(median) || median <= 0
                        || !Double.isFinite(p25) || p25 <= 0 || !Double.isFinite(p75) || p75 <= 0) {
                    throw new IllegalArgumentException("Non-finite calibrated throughput");
                }
                accepted.add(new Accepted(run, median, p25, p75));
            }
        }
        if (accepted.isEmpty()) {
            return empty(scenario, policy,
                    hasValidRun ? ScenarioResultStatus.NO_ACCEPTED_CALIBRATION
                            : ScenarioResultStatus.NO_VALID_RUN, matching.size(), weak,
                    uncalibrated);
        }
        accepted.sort(Comparator.comparing(item -> item.run.run().descriptor().benchmarkRunId()));
        double[] medians = accepted.stream().mapToDouble(Accepted::median).toArray();
        double p25 = VectorStatistics.quantileType7(medians, 0.25);
        double median = VectorStatistics.median(medians);
        double p75 = VectorStatistics.quantileType7(medians, 0.75);
        double[] relativeIqrs =
                accepted.stream().mapToDouble(item -> (item.p75 - item.p25) / item.median)
                        .toArray();
        double[] timeouts = accepted.stream().mapToDouble(item -> item.run.timeoutRate()).toArray();
        double[] failures = accepted.stream().mapToDouble(item -> item.run.failureRate()).toArray();
        double[] nonSuccesses =
                accepted.stream().mapToDouble(item -> item.run.nonSuccessRate()).toArray();
        double[] bounds = bootstrap(medians, policy, scenario, config);
        int successful =
                accepted.stream().mapToInt(item -> item.run.successfulRepetitionCount()).sum();
        int planned = accepted.stream().mapToInt(item -> item.run.plannedRepetitionCount()).sum();
        boolean acceptedWeak = accepted.stream().anyMatch(
                item -> calibrations.get(item.run.run().descriptor().benchmarkRunId()).status()
                        == CalibrationStatus.WEAKLY_CALIBRATED);
        return new ScenarioResult(scenario, policy,
                acceptedWeak ? ScenarioResultStatus.VALID_WEAK_OVERRIDE
                        : ScenarioResultStatus.VALID_STRONG, matching.size(), accepted.size(), weak,
                uncalibrated, successful, planned, OptionalDouble.of(p25),
                OptionalDouble.of(median), OptionalDouble.of(p75), OptionalDouble.of(p75 - p25),
                OptionalDouble.of(VectorStatistics.median(relativeIqrs)),
                OptionalDouble.of(VectorStatistics.compensatedMean(timeouts)),
                OptionalDouble.of(VectorStatistics.compensatedMean(failures)),
                OptionalDouble.of(VectorStatistics.compensatedMean(nonSuccesses)),
                OptionalDouble.of(bounds[0]), OptionalDouble.of(bounds[1]), OptionalDouble.empty());
    }

    private static double[] bootstrap(double[] medians, PolicyVector policy,
            SourceScenario scenario, AggregationConfig config) {
        double point = VectorStatistics.median(medians);
        if (medians.length == 1) {
            return new double[] {point, point};
        }
        long seed = config.bootstrapSeed() ^ policy.id().value() ^ HasherApi.getHash(
                scenario.canonical(), config.bootstrapSeed());
        Random random = new Random(seed);
        double[] replicates = new double[config.bootstrapReplicates()];
        double[] sample = new double[medians.length];
        for (int r = 0; r < replicates.length; r++) {
            for (int i = 0; i < sample.length; i++) {
                sample[i] = medians[random.nextInt(medians.length)];
            }
            replicates[r] = VectorStatistics.median(sample);
        }
        return new double[] {VectorStatistics.quantileType7(replicates, 0.025),
                VectorStatistics.quantileType7(replicates, 0.975)};
    }

    private static ScenarioResult empty(SourceScenario scenario, PolicyVector policy,
            ScenarioResultStatus status, int total, int weak, int uncalibrated) {
        OptionalDouble empty = OptionalDouble.empty();
        return new ScenarioResult(scenario, policy, status, total, 0, weak, uncalibrated, 0, 0,
                empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty);
    }

    private HierarchicalAggregator() {
    }

    private record Accepted(RunAggregate run, double median, double p25, double p75) {

    }
}
