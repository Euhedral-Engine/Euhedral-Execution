package io.euhedral_execution.training.merge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.config.AnchorSelectionConfig;
import io.euhedral_execution.training.merge.data.AnchorCatalog;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.merge.data.MergeRecords;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregate;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregateStatus;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResult;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import io.euhedral_execution.training.merge.data.ReferenceRunCatalog;

public final class AnchorBootstrapper {

    public static CalibrationPlan bootstrap(List<RunAggregate> rawRunAggregates,
            SortedSet<SourceScenario> requiredScenarios, int policyBudget,
            Map<SourceScenario, String> referenceOverrides, AnchorSelectionConfig anchorConfig,
            AggregationConfig aggregationConfig) {
        Objects.requireNonNull(rawRunAggregates);
        Objects.requireNonNull(requiredScenarios);
        Objects.requireNonNull(referenceOverrides);
        Objects.requireNonNull(anchorConfig);
        Objects.requireNonNull(aggregationConfig);
        if (requiredScenarios.isEmpty()) {
            throw new IllegalArgumentException("No required scenarios");
        }
        int target = anchorConfig.targetCount(policyBudget);
        SortedMap<SourceScenario, String> references = new TreeMap<>();
        Map<String, List<RunAggregate>> byRun = groupByRun(rawRunAggregates);
        for (SourceScenario scenario : requiredScenarios) {
            String override = referenceOverrides.get(scenario);
            if (override != null) {
                List<RunAggregate> run = requireRun(byRun, override, scenario);
                if (!bootstrapOriginAllowed(run.getFirst(), anchorConfig)) {
                    throw new IllegalArgumentException(
                            "Imported reference is not allowed: " + override);
                }
                references.put(scenario, override);
                continue;
            }
            String selected = byRun.values().stream()
                    .filter(rows -> rows.getFirst().run().descriptor().scenario().equals(scenario))
                    .filter(rows -> bootstrapOriginAllowed(rows.getFirst(), anchorConfig))
                    .filter(rows ->
                            rows.stream().filter(row -> validBootstrap(row, anchorConfig)).count()
                                    >= target).sorted(Comparator.comparing(
                            (List<RunAggregate> rows) -> rows.getFirst().run().descriptor()
                                    .startedAt()).thenComparing(
                            rows -> rows.getFirst().run().descriptor().benchmarkRunId()))
                    .map(rows -> rows.getFirst().run().descriptor().benchmarkRunId()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No bootstrap reference for " + scenario.canonical()));
            references.put(scenario, selected);
        }
        SortedMap<PolicyId, PolicyVector> common = null;
        for (String runId : references.values()) {
            SortedMap<PolicyId, PolicyVector> valid = new TreeMap<>();
            for (RunAggregate row : byRun.get(runId)) {
                if (validBootstrap(row, anchorConfig)) {
                    valid.put(row.policy().id(), row.policy());
                }
            }
            if (common == null) {
                common = valid;
            } else {
                common.keySet().retainAll(valid.keySet());
            }
        }
        if (common == null || common.size() < target) {
            throw new IllegalArgumentException(
                    "Bootstrap intersection has " + (common == null ? 0 : common.size())
                            + " policies; requires " + target + "; references="
                            + references.values());
        }
        List<ScenarioResult> provisional = new ArrayList<>();
        for (SourceScenario scenario : requiredScenarios) {
            String runId = references.get(scenario);
            Map<PolicyId, RunAggregate> rows = new HashMap<>();
            byRun.get(runId).forEach(row -> rows.put(row.policy().id(), row));
            for (PolicyVector policy : common.values()) {
                RunAggregate row = rows.get(policy.id());
                provisional.add(provisional(row));
            }
        }
        List<ScenarioResult> ranked = ScenarioQualityRanker.assignQualities(provisional);
        List<MergeRecords.RobustPolicySummary> summaries = new ArrayList<>(
                ScenarioQualityRanker.summarize(common.values(), ranked, requiredScenarios));
        summaries.sort(AnchorBootstrapper::compareWorstFirst);
        int population = summaries.size();
        SortedMap<PolicyId, PolicyVector> selected = new TreeMap<>();
        for (int i = 0; i < target; i++) {
            int index = (int) StrictMath.floor((i + 0.5) * population / target);
            PolicyVector policy = summaries.get(index).policy();
            selected.put(policy.id(), policy);
        }
        if (selected.size() != target) {
            throw new IllegalStateException("Strata selected duplicates");
        }
        AnchorCatalog catalog = AnchorCatalog.of(List.copyOf(selected.values()));
        return new CalibrationPlan(catalog,
                new ReferenceRunCatalog(1, catalog.anchorSetId(), references));
    }

    private static ScenarioResult provisional(RunAggregate row) {
        return new ScenarioResult(row.run().descriptor().scenario(), row.policy(),
                ScenarioResultStatus.VALID_STRONG, 1, 1, 0, 0, row.successfulRepetitionCount(),
                row.plannedRepetitionCount(), row.rawP25(), row.rawMedian(), row.rawP75(),
                row.rawIqr(),
                OptionalDouble.of(row.rawIqr().getAsDouble() / row.rawMedian().getAsDouble()),
                OptionalDouble.of(row.timeoutRate()), OptionalDouble.of(row.failureRate()),
                OptionalDouble.of(row.nonSuccessRate()), row.rawMedian(), row.rawMedian(),
                OptionalDouble.empty());
    }

    private static boolean validBootstrap(RunAggregate row, AnchorSelectionConfig config) {
        return row.status() == RunAggregateStatus.VALID && row.successfulRepetitionCount() >= 3
                && row.successRate() >= 0.5
                && row.nonSuccessRate() <= config.maximumBootstrapNonSuccessRate()
                && row.rawMedian().isPresent() && row.rawMedian().getAsDouble() > 0
                && row.rawIqr().getAsDouble() / row.rawMedian().getAsDouble()
                <= config.maximumBootstrapRelativeIqr();
    }

    private static boolean bootstrapOriginAllowed(RunAggregate row, AnchorSelectionConfig config) {
        return row.run().descriptor().evidenceOrigin() == EvidenceOrigin.NATIVE
                || config.allowImportedBootstrap();
    }

    private static List<RunAggregate> requireRun(Map<String, List<RunAggregate>> byRun,
            String runId, SourceScenario scenario) {
        List<RunAggregate> rows = byRun.get(runId);
        if (rows == null || !rows.getFirst().run().descriptor().scenario().equals(scenario)) {
            throw new IllegalArgumentException("Reference override does not match scenario");
        }
        return rows;
    }

    private static Map<String, List<RunAggregate>> groupByRun(List<RunAggregate> rows) {
        Map<String, List<RunAggregate>> result = new TreeMap<>();
        for (RunAggregate row : rows) {
            List<RunAggregate> group =
                    result.computeIfAbsent(row.run().descriptor().benchmarkRunId(),
                            ignored -> new ArrayList<>());
            if ((!group.isEmpty() && !group.getFirst().run().equals(row.run())) || group.stream()
                    .anyMatch(existing -> existing.policy().id().equals(row.policy().id()))) {
                throw new IllegalArgumentException("Ambiguous bootstrap run aggregate");
            }
            group.add(row);
        }
        return result;
    }

    private static int compareWorstFirst(MergeRecords.RobustPolicySummary left,
            MergeRecords.RobustPolicySummary right) {
        int result = Double.compare(left.worstQuality().getAsDouble(),
                right.worstQuality().getAsDouble());
        if (result == 0) {
            result = Double.compare(left.qualityP25().getAsDouble(),
                    right.qualityP25().getAsDouble());
        }
        if (result == 0) {
            result = Double.compare(left.geometricMeanQuality().getAsDouble(),
                    right.geometricMeanQuality().getAsDouble());
        }
        if (result == 0) {
            result = Double.compare(right.crossScenarioQualityMad().getAsDouble(),
                    left.crossScenarioQualityMad().getAsDouble());
        }
        if (result == 0) {
            result = Double.compare(right.medianRelativeIqr().getAsDouble(),
                    left.medianRelativeIqr().getAsDouble());
        }
        if (result == 0) {
            result = Double.compare(right.meanNonSuccessRate().getAsDouble(),
                    left.meanNonSuccessRate().getAsDouble());
        }
        return result != 0 ? result : left.policy().id().compareTo(right.policy().id());
    }

    private AnchorBootstrapper() {
    }
}
