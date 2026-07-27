package io.euhedral_execution.training.merge;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyRole;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.MergeRecords.RunAggregate;
import io.euhedral_execution.training.merge.MergeRecords.RunAggregateStatus;
import io.euhedral_execution.training.merge.MergeRecords.RunCalibration;
import java.util.*;

public final class RunCalibrator {
    public static List<RunCalibration> calibrate(List<RunAggregate> runs, CalibrationPlan plan,
            CalibrationConfig config) {
        Map<Key, RunAggregate> byKey = new HashMap<>();
        Map<String, io.euhedral_execution.training.data.BenchmarkRunContext> contexts
                = new HashMap<>();
        for (RunAggregate run : runs) {
            if (byKey.put(new Key(run.run().descriptor().benchmarkRunId(),
                    run.policy().id()), run) != null) {
                throw new IllegalArgumentException("Duplicate run/policy aggregate");
            }
            var existingContext = contexts.putIfAbsent(
                    run.run().descriptor().benchmarkRunId(), run.run());
            if (existingContext != null && !existingContext.equals(run.run())) {
                throw new IllegalArgumentException("Ambiguous run context");
            }
        }
        for (Map.Entry<SourceScenario, String> reference
                : plan.references().referenceRunIds().entrySet()) {
            boolean present = runs.stream().anyMatch(run ->
                    run.run().descriptor().benchmarkRunId().equals(reference.getValue())
                            && run.run().descriptor().scenario().equals(reference.getKey()));
            if (!present) {
                throw new IllegalArgumentException("Missing reference run "
                        + reference.getValue() + " for " + reference.getKey().canonical());
            }
        }
        Set<PolicyId> anchorIds = new TreeSet<>();
        plan.anchors().fixedAnchors().forEach(policy -> anchorIds.add(policy.id()));
        Map<String, List<RunAggregate>> grouped = new TreeMap<>();
        for (RunAggregate run : runs) grouped.computeIfAbsent(run.run().descriptor().benchmarkRunId(),
                ignored -> new ArrayList<>()).add(run);
        List<RunCalibration> result = new ArrayList<>();
        for (List<RunAggregate> group : grouped.values()) {
            RunAggregate first = group.getFirst();
            SourceScenario scenario = first.run().descriptor().scenario();
            String referenceId = plan.references().referenceRunIds().get(scenario);
            if (referenceId == null) {
                result.add(new RunCalibration(first.run(), "", plan.anchors().anchorSetId(),
                        anchorIds.size(), 0, OptionalDouble.empty(), OptionalDouble.empty(),
                        OptionalDouble.empty(), CalibrationStatus.UNCALIBRATED,
                        "MISSING_SCENARIO_REFERENCE", new TreeMap<>()));
                continue;
            }
            if (first.run().descriptor().benchmarkRunId().equals(referenceId)) {
                int qualifying = (int) group.stream().filter(run ->
                        anchorIds.contains(run.policy().id()) && qualifies(run)).count();
                if (qualifying < 5) {
                    throw new IllegalArgumentException("Reference " + referenceId
                            + " has only " + qualifying + " valid fixed anchors");
                }
                result.add(new RunCalibration(first.run(), referenceId, plan.anchors().anchorSetId(),
                        anchorIds.size(), qualifying, OptionalDouble.of(0),
                        OptionalDouble.of(1), OptionalDouble.of(0), CalibrationStatus.REFERENCE,
                        "REFERENCE_RUN", new TreeMap<>()));
                continue;
            }
            List<AnchorDifference> differences = new ArrayList<>();
            for (RunAggregate run : group) {
                if (!anchorIds.contains(run.policy().id())
                        || !run.roles().contains(PolicyRole.FIXED_ANCHOR)
                        || !qualifies(run)) continue;
                RunAggregate reference = byKey.get(new Key(referenceId, run.policy().id()));
                if (reference == null || !qualifies(reference)) continue;
                double currentSe = medianSe(run, config);
                double referenceSe = medianSe(reference, config);
                double delta = StrictMath.log(run.rawMedian().getAsDouble()
                        / reference.rawMedian().getAsDouble());
                double rawWeight = 1 / (currentSe * currentSe + referenceSe * referenceSe);
                if (Double.isFinite(delta) && Double.isFinite(rawWeight) && rawWeight > 0) {
                    differences.add(new AnchorDifference(run.policy().id(), delta, rawWeight));
                }
            }
            result.add(calibration(first, referenceId, plan, differences, config));
        }
        result.sort(Comparator.comparing((RunCalibration row) -> row.run().descriptor().scenario())
                .thenComparing(row -> row.status() == CalibrationStatus.REFERENCE ? "" :
                        row.run().descriptor().benchmarkRunId()));
        return List.copyOf(result);
    }

    private static RunCalibration calibration(RunAggregate first, String referenceId,
            CalibrationPlan plan, List<AnchorDifference> differences, CalibrationConfig config) {
        int count = differences.size();
        if (count < config.minimumWeakAnchors()) return new RunCalibration(first.run(), referenceId,
                plan.anchors().anchorSetId(), plan.anchors().fixedAnchors().size(), count,
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
                CalibrationStatus.UNCALIBRATED, "INSUFFICIENT_SHARED_ANCHORS", new TreeMap<>());
        differences.sort(Comparator.comparing(AnchorDifference::id));
        double[] raw = differences.stream().mapToDouble(AnchorDifference::rawWeight).toArray();
        double cap = Math.max(config.maximumAnchorWeightShare(), 1.0 / count);
        double[] weights = RobustStatistics.capAndNormalizeWeights(raw, cap);
        List<WeightedValue<PolicyId>> values = new ArrayList<>();
        SortedMap<PolicyId, Double> stored = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            values.add(new WeightedValue<>(differences.get(i).delta(), weights[i],
                    differences.get(i).id()));
            stored.put(differences.get(i).id(), weights[i]);
        }
        double delta = RobustStatistics.weightedMedian(values);
        List<WeightedValue<PolicyId>> residuals = values.stream().map(item -> new WeightedValue<>(
                StrictMath.abs(item.value() - delta), item.weight(), item.tieBreaker())).toList();
        double residual = RobustStatistics.weightedMedian(residuals);
        double scale = StrictMath.exp(delta);
        CalibrationStatus status;
        String reason;
        if (!Double.isFinite(scale) || scale <= 0) {
            status = CalibrationStatus.UNCALIBRATED; reason = "NONFINITE_SCALE";
        } else if (count >= config.minimumStrongAnchors()
                && residual <= config.maximumStrongResidual()) {
            status = CalibrationStatus.CALIBRATED; reason = "STRONG";
        } else if (residual <= config.maximumWeakResidual()) {
            status = CalibrationStatus.WEAKLY_CALIBRATED;
            reason = count < config.minimumStrongAnchors() ? "WEAK_ANCHOR_COUNT" : "WEAK_RESIDUAL";
        } else {
            status = CalibrationStatus.UNCALIBRATED; reason = "EXCESSIVE_RESIDUAL";
        }
        return new RunCalibration(first.run(), referenceId, plan.anchors().anchorSetId(),
                plan.anchors().fixedAnchors().size(), count, OptionalDouble.of(delta),
                OptionalDouble.of(scale), OptionalDouble.of(residual), status, reason, stored);
    }

    private static double medianSe(RunAggregate run, CalibrationConfig config) {
        double effective = run.successfulRepetitionCount() * run.successRate();
        double sigma = Math.max(run.rawLogIqr().getAsDouble() / 1.3489795003921634,
                config.minimumLogSigma());
        return 1.2533141373155001 * sigma / StrictMath.sqrt(effective);
    }
    private static boolean qualifies(RunAggregate run) {
        return run.status() == RunAggregateStatus.VALID
                && run.successfulRepetitionCount() >= 3 && run.successRate() >= 0.5
                && run.rawMedian().isPresent() && Double.isFinite(run.rawMedian().getAsDouble())
                && run.rawMedian().getAsDouble() > 0;
    }
    private record Key(String runId, PolicyId policyId) {}
    private record AnchorDifference(PolicyId id, double delta, double rawWeight) {}
    private RunCalibrator() {
    }
}
