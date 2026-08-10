package io.euhedral_execution.training.scheduling.data;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.data.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public record OptimizationCorpusView(
        SortedMap<PolicyId, PolicyVector> policies,
        List<RobustPolicySummary> eligiblePolicies,
        SortedMap<PolicyId, RobustPolicySummary> summaries,
        SortedMap<PolicyId, SortedMap<SourceScenario, ScenarioResultStatus>> coverage,
        String mergeArtifactSha256) {
    public OptimizationCorpusView {
        policies = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(policies));
        eligiblePolicies = List.copyOf(eligiblePolicies);
        summaries = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(summaries));
        TreeMap<PolicyId, SortedMap<SourceScenario, ScenarioResultStatus>> copy = new TreeMap<>();
        coverage.forEach(
                (policy, rows) -> copy.put(policy, java.util.Collections.unmodifiableSortedMap(new TreeMap<>(rows))));
        coverage = java.util.Collections.unmodifiableSortedMap(copy);
        if (mergeArtifactSha256 == null || mergeArtifactSha256.isBlank()) {
            throw new IllegalArgumentException("Merge artifact fingerprint is required");
        }
    }
}
