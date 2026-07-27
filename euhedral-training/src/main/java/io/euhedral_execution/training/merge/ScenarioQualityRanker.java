package io.euhedral_execution.training.merge;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.merge.MergeRecords.ScenarioResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.SortedSet;
import java.util.TreeSet;

public final class ScenarioQualityRanker {
    public static final double QUALITY_EPSILON = 1.0e-12;

    public static List<ScenarioResult> assignQualities(List<ScenarioResult> scenarioResults) {
        Map<SourceScenario, List<ScenarioResult>> grouped = new HashMap<>();
        java.util.Set<Key> seen = new java.util.HashSet<>();
        for (ScenarioResult result : scenarioResults) {
            if (!seen.add(new Key(result.scenario(), result.policy()))) {
                throw new IllegalArgumentException("Duplicate policy/scenario result");
            }
            if (result.throughputMedian().isPresent()) {
                grouped.computeIfAbsent(result.scenario(), ignored -> new ArrayList<>()).add(result);
            }
        }
        Map<Key, Double> qualities = new HashMap<>();
        for (List<ScenarioResult> rows : grouped.values()) {
            rows.sort(Comparator.comparingDouble(row -> row.throughputMedian().getAsDouble()));
            int start = 0;
            while (start < rows.size()) {
                int end = start;
                while (end + 1 < rows.size() && Double.compare(
                        rows.get(start).throughputMedian().getAsDouble(),
                        rows.get(end + 1).throughputMedian().getAsDouble()) == 0) end++;
                double quality = rows.size() == 1 ? 0.5
                        : (start + end) / (2.0 * (rows.size() - 1));
                for (int i = start; i <= end; i++) {
                    qualities.put(new Key(rows.get(i).scenario(), rows.get(i).policy()), quality);
                }
                start = end + 1;
            }
        }
        List<ScenarioResult> result = new ArrayList<>(scenarioResults.size());
        for (ScenarioResult row : scenarioResults) {
            Double quality = qualities.get(new Key(row.scenario(), row.policy()));
            result.add(quality == null ? row : row.withQuality(quality));
        }
        result.sort(Comparator.comparing(ScenarioResult::scenario)
                .thenComparing(row -> row.policy().id()));
        return List.copyOf(result);
    }

    public static List<RobustPolicySummary> summarize(Collection<PolicyVector> policies,
            List<ScenarioResult> scenarioResults, SortedSet<SourceScenario> requiredScenarios) {
        if (requiredScenarios.isEmpty()) throw new IllegalArgumentException("No required scenarios");
        Map<Key, ScenarioResult> rows = new HashMap<>();
        for (ScenarioResult row : scenarioResults) {
            if (rows.put(new Key(row.scenario(), row.policy()), row) != null) {
                throw new IllegalArgumentException("Duplicate policy/scenario result");
            }
        }
        List<RobustPolicySummary> summaries = new ArrayList<>();
        java.util.Set<io.euhedral_execution.training.data.PolicyId> policyIds
                = new java.util.HashSet<>();
        for (PolicyVector policy : policies) {
            if (!policyIds.add(policy.id())) {
                throw new IllegalArgumentException("Duplicate policy");
            }
            SortedSet<SourceScenario> measured = new TreeSet<>();
            SortedSet<SourceScenario> missing = new TreeSet<>();
            SortedSet<SourceScenario> rejected = new TreeSet<>();
            List<ScenarioResult> valid = new ArrayList<>();
            int observed = 0;
            for (SourceScenario scenario : requiredScenarios) {
                ScenarioResult row = rows.get(new Key(scenario, policy));
                if (row == null || row.totalRunCount() == 0) {
                    missing.add(scenario);
                } else {
                    observed++;
                    if (row.quality().isPresent()) {
                        valid.add(row);
                        measured.add(scenario);
                    } else rejected.add(scenario);
                }
            }
            boolean eligible = valid.size() == requiredScenarios.size();
            OptionalDouble empty = OptionalDouble.empty();
            OptionalDouble worst = empty, p25 = empty, geometric = empty, mad = empty;
            OptionalDouble relativeIqr = empty, nonSuccess = empty, timeout = empty;
            if (eligible) {
                double[] qualities = valid.stream().mapToDouble(
                        row -> row.quality().getAsDouble()).toArray();
                double[] logs = new double[qualities.length];
                double[] iqrs = new double[qualities.length];
                double[] nonSuccesses = new double[qualities.length];
                double[] timeouts = new double[qualities.length];
                for (int i = 0; i < qualities.length; i++) {
                    logs[i] = StrictMath.log(Math.max(qualities[i], QUALITY_EPSILON));
                    iqrs[i] = valid.get(i).medianWithinRunRelativeIqr().getAsDouble();
                    nonSuccesses[i] = valid.get(i).meanNonSuccessRate().getAsDouble();
                    timeouts[i] = valid.get(i).meanTimeoutRate().getAsDouble();
                }
                worst = OptionalDouble.of(java.util.Arrays.stream(qualities).min().orElseThrow());
                p25 = OptionalDouble.of(RobustStatistics.quantileType7(qualities, 0.25));
                geometric = OptionalDouble.of(StrictMath.exp(RobustStatistics.compensatedMean(logs)));
                mad = OptionalDouble.of(RobustStatistics.mad(qualities));
                relativeIqr = OptionalDouble.of(RobustStatistics.median(iqrs));
                nonSuccess = OptionalDouble.of(RobustStatistics.compensatedMean(nonSuccesses));
                timeout = OptionalDouble.of(RobustStatistics.compensatedMean(timeouts));
            }
            summaries.add(new RobustPolicySummary(policy, eligible, requiredScenarios.size(),
                    observed, valid.size(), valid.size() / (double) requiredScenarios.size(),
                    worst, p25, geometric, mad, relativeIqr, nonSuccess, timeout,
                    measured, missing, rejected));
        }
        summaries.sort(RobustPolicyComparator.PUBLISHED_ORDER);
        return List.copyOf(summaries);
    }

    private record Key(SourceScenario scenario, PolicyVector policy) {
    }

    private ScenarioQualityRanker() {
    }
}
