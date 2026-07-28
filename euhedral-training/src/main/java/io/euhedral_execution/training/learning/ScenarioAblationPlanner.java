package io.euhedral_execution.training.learning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;

final class ScenarioAblationPlanner {
    private ScenarioAblationPlanner() {
    }

    static Decision decide(FeatureSelectionMode mode,
            List<EvaluationSummary> policyContextFolds,
            List<EvaluationSummary> ratioContextFolds,
            List<EvaluationSummary> ratioEnvironmentFolds,
            List<EvaluationSummary> countEnvironmentFolds,
            int environmentCount, EvaluationThresholds thresholds) {
        Aggregate policy = aggregate(policyContextFolds);
        Aggregate ratio = aggregate(ratioContextFolds);
        ArrayList<AblationMetric> metrics = new ArrayList<>();
        addComparedMetrics(metrics, "VALIDATION_CONTEXT_LOSO", policyContextFolds,
                ratioContextFolds, false);
        boolean contextMetrics = sameFoldIds(policyContextFolds, ratioContextFolds)
                && policy.ok() && ratio.ok()
                && policy.mae().isPresent() && ratio.mae().isPresent()
                && policy.spearman().isPresent() && ratio.spearman().isPresent();
        double contextMaeDelta = contextMetrics
                ? ratio.mae().getAsDouble() - policy.mae().getAsDouble() : Double.NaN;
        double contextSpearmanDelta = contextMetrics
                ? ratio.spearman().getAsDouble() - policy.spearman().getAsDouble() : Double.NaN;
        boolean useful = contextMetrics
                && (ratio.mae().getAsDouble() <= policy.mae().getAsDouble()
                - thresholds.minimumContextMaeImprovement()
                || ratio.spearman().getAsDouble() >= policy.spearman().getAsDouble()
                + thresholds.minimumContextSpearmanImprovement());
        boolean contextPassed = useful
                && ratio.mae().getAsDouble() <= policy.mae().getAsDouble()
                + thresholds.maximumContextMaeRegression()
                && ratio.spearman().getAsDouble() >= policy.spearman().getAsDouble()
                - thresholds.maximumContextSpearmanRegression();
        metrics.add(aggregateMetric("VALIDATION_CONTEXT_GATE", "all",
                ScenarioFeatureSet.RATIO_ONLY, ScenarioFeatureSet.POLICY_ONLY, "all",
                ratio, contextMaeDelta, contextSpearmanDelta, contextPassed,
                contextMetrics ? contextPassed ? "PASS" : "FAIL" : "NOT_EVALUABLE",
                contextMetrics ? contextPassed ? "CONTEXT_VALIDATED" : "CONTEXT_GATE_FAILED"
                        : "CONTEXT_METRIC_MISSING"));

        if (mode == FeatureSelectionMode.RATIO_ONLY) {
            return new Decision(new FeatureSelectionDecision(mode, ScenarioFeatureSet.RATIO_ONLY,
                    metrics, "RATIO_ONLY_REQUESTED"), contextPassed, true);
        }
        if (environmentCount < 2) {
            boolean countsPassed = false;
            ScenarioFeatureSet selected = mode == FeatureSelectionMode.REQUIRE_COUNTS
                    ? ScenarioFeatureSet.RATIO_AND_COUNTS : ScenarioFeatureSet.RATIO_ONLY;
            metrics.add(new AblationMetric("VALIDATION_COUNTS_LOEO", "all",
                    ScenarioFeatureSet.RATIO_AND_COUNTS, ScenarioFeatureSet.RATIO_ONLY,
                    "all", 0, OptionalDouble.empty(), OptionalDouble.empty(),
                    OptionalDouble.empty(), OptionalDouble.empty(), false, "NOT_EVALUABLE",
                    "INSUFFICIENT_ENVIRONMENTS"));
            return new Decision(new FeatureSelectionDecision(mode, selected, metrics,
                    "INSUFFICIENT_ENVIRONMENTS"), contextPassed, countsPassed);
        }
        Aggregate ratioEnvironment = aggregate(ratioEnvironmentFolds);
        Aggregate countsEnvironment = aggregate(countEnvironmentFolds);
        addComparedMetrics(metrics, "VALIDATION_COUNTS_LOEO", ratioEnvironmentFolds,
                countEnvironmentFolds, false);
        boolean countMetrics = ratioEnvironmentFolds.size() == environmentCount
                && sameFoldIds(ratioEnvironmentFolds, countEnvironmentFolds)
                && ratioEnvironment.ok() && countsEnvironment.ok()
                && ratioEnvironment.mae().isPresent() && countsEnvironment.mae().isPresent()
                && ratioEnvironment.spearman().isPresent()
                && countsEnvironment.spearman().isPresent()
                && ratioEnvironment.worstMae().isPresent()
                && countsEnvironment.worstMae().isPresent();
        double countMaeDelta = countMetrics
                ? countsEnvironment.mae().getAsDouble()
                - ratioEnvironment.mae().getAsDouble() : Double.NaN;
        double countSpearmanDelta = countMetrics
                ? countsEnvironment.spearman().getAsDouble()
                - ratioEnvironment.spearman().getAsDouble() : Double.NaN;
        double worstDelta = countMetrics
                ? countsEnvironment.worstMae().getAsDouble()
                - ratioEnvironment.worstMae().getAsDouble() : Double.NaN;
        boolean countsPassed = countMetrics
                && countsEnvironment.mae().getAsDouble()
                <= ratioEnvironment.mae().getAsDouble()
                - thresholds.minimumCountsCrossEnvironmentMaeImprovement()
                && countsEnvironment.spearman().getAsDouble()
                >= ratioEnvironment.spearman().getAsDouble()
                - thresholds.maximumCountsSpearmanRegression()
                && countsEnvironment.worstMae().getAsDouble()
                <= ratioEnvironment.worstMae().getAsDouble()
                + thresholds.maximumCountsWorstEnvironmentMaeRegression();
        metrics.add(aggregateMetric("VALIDATION_COUNTS_GATE", "all",
                ScenarioFeatureSet.RATIO_AND_COUNTS, ScenarioFeatureSet.RATIO_ONLY, "all",
                countsEnvironment, countMaeDelta, countSpearmanDelta, countsPassed,
                countMetrics ? countsPassed ? "PASS" : "FAIL" : "NOT_EVALUABLE",
                countMetrics ? countsPassed ? "COUNTS_VALIDATED" : "COUNTS_GATE_FAILED"
                        : "COUNTS_METRIC_MISSING"));
        ScenarioFeatureSet selected = countsPassed || mode == FeatureSelectionMode.REQUIRE_COUNTS
                ? ScenarioFeatureSet.RATIO_AND_COUNTS : ScenarioFeatureSet.RATIO_ONLY;
        String reason = countsPassed ? "COUNTS_CROSS_ENVIRONMENT_VALIDATED"
                : mode == FeatureSelectionMode.REQUIRE_COUNTS
                ? "REQUIRED_COUNTS_GATE_FAILED" : "COUNTS_GATE_FALLBACK_RATIO";
        return new Decision(new FeatureSelectionDecision(mode, selected, metrics, reason),
                contextPassed, countsPassed);
    }

    private static void addComparedMetrics(List<AblationMetric> destination, String kind,
            List<EvaluationSummary> baseline, List<EvaluationSummary> candidate,
            boolean selected) {
        TreeMap<String, EvaluationSummary> baselines = byFold(baseline);
        TreeMap<String, EvaluationSummary> candidates = byFold(candidate);
        for (Map.Entry<String, EvaluationSummary> entry : baselines.entrySet()) {
            EvaluationSummary left = entry.getValue();
            EvaluationSummary right = candidates.get(entry.getKey());
            if (right == null) continue;
            OptionalDouble maeDelta = delta(right.macroMae(), left.macroMae());
            OptionalDouble spearmanDelta =
                    delta(right.macroSpearman(), left.macroSpearman());
            int rows = right.scenarios().stream()
                    .mapToInt(ScenarioEvaluationMetrics::rowCount).sum();
            String target = right.scenarios().stream().map(metric ->
                    metric.scenario().canonical()).sorted().reduce((a, b) -> a + ";" + b)
                    .orElse("");
            destination.add(new AblationMetric(kind, entry.getKey(), right.featureSet(),
                    left.featureSet(), target, rows, right.macroMae(), right.macroSpearman(),
                    maeDelta, spearmanDelta, selected,
                    allOk(right) ? "OK" : "FOLD_FAILED",
                    allOk(right) ? "FOLD_EVALUATED" : "NON_OK_SCENARIO"));
            destination.add(new AblationMetric(kind, entry.getKey(), left.featureSet(),
                    right.featureSet(), target, rows, left.macroMae(), left.macroSpearman(),
                    OptionalDouble.empty(), OptionalDouble.empty(), false,
                    allOk(left) ? "OK" : "FOLD_FAILED",
                    allOk(left) ? "NEGATIVE_CONTROL" : "NON_OK_SCENARIO"));
        }
    }

    private static AblationMetric aggregateMetric(String kind, String fold,
            ScenarioFeatureSet feature, ScenarioFeatureSet comparison, String target,
            Aggregate aggregate, double maeDelta, double spearmanDelta, boolean selected,
            String status, String reason) {
        return new AblationMetric(kind, fold, feature, comparison, target, aggregate.rows(),
                aggregate.mae(), aggregate.spearman(),
                optionalFinite(maeDelta), optionalFinite(spearmanDelta), selected, status, reason);
    }

    private static Aggregate aggregate(List<EvaluationSummary> folds) {
        if (folds.isEmpty()) {
            return new Aggregate(false, 0, OptionalDouble.empty(), OptionalDouble.empty(),
                    OptionalDouble.empty());
        }
        boolean ok = folds.stream().allMatch(ScenarioAblationPlanner::allOk);
        int rows = folds.stream().flatMap(fold -> fold.scenarios().stream())
                .mapToInt(ScenarioEvaluationMetrics::rowCount).sum();
        OptionalDouble mae = average(folds.stream().map(EvaluationSummary::macroMae).toList());
        OptionalDouble spearman =
                average(folds.stream().map(EvaluationSummary::macroSpearman).toList());
        OptionalDouble worst = folds.stream().allMatch(fold -> fold.macroMae().isPresent())
                ? OptionalDouble.of(folds.stream().mapToDouble(
                        fold -> fold.macroMae().getAsDouble()).max().orElseThrow())
                : OptionalDouble.empty();
        return new Aggregate(ok, rows, mae, spearman, worst);
    }

    private static boolean allOk(EvaluationSummary summary) {
        return !summary.scenarios().isEmpty() && summary.scenarios().stream()
                .allMatch(metric -> metric.status() == EvaluationStatus.OK);
    }

    private static TreeMap<String, EvaluationSummary> byFold(List<EvaluationSummary> values) {
        TreeMap<String, EvaluationSummary> result = new TreeMap<>();
        for (EvaluationSummary value : values) {
            String fold = value.scenarios().isEmpty()
                    ? "" : value.scenarios().getFirst().foldId();
            if (result.put(fold, value) != null) {
                throw new IllegalArgumentException("Duplicate ablation fold " + fold);
            }
        }
        return result;
    }

    private static boolean sameFoldIds(List<EvaluationSummary> baseline,
            List<EvaluationSummary> candidate) {
        return !baseline.isEmpty()
                && byFold(baseline).keySet().equals(byFold(candidate).keySet());
    }

    private static OptionalDouble delta(OptionalDouble candidate, OptionalDouble baseline) {
        return candidate.isPresent() && baseline.isPresent()
                ? OptionalDouble.of(candidate.getAsDouble() - baseline.getAsDouble())
                : OptionalDouble.empty();
    }

    private static OptionalDouble average(List<OptionalDouble> values) {
        if (values.isEmpty() || values.stream().anyMatch(OptionalDouble::isEmpty)) {
            return OptionalDouble.empty();
        }
        double sum = 0;
        double correction = 0;
        for (OptionalDouble value : values) {
            double addend = value.getAsDouble();
            double next = sum + addend;
            correction += StrictMath.abs(sum) >= StrictMath.abs(addend)
                    ? (sum - next) + addend : (addend - next) + sum;
            sum = next;
        }
        return OptionalDouble.of((sum + correction) / values.size());
    }

    private static OptionalDouble optionalFinite(double value) {
        return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    record Decision(FeatureSelectionDecision selection, boolean contextPassed,
            boolean countsPassed) {
    }

    private record Aggregate(boolean ok, int rows, OptionalDouble mae,
            OptionalDouble spearman, OptionalDouble worstMae) {
    }
}
