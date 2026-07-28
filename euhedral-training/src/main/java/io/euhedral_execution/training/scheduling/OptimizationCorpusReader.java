package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.DataMerger;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.merge.MergeRecords.ScenarioResultStatus;
import java.io.IOException;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;

public final class OptimizationCorpusReader {
    public static OptimizationCorpusView read(DataMerger.MergeArtifacts artifacts,
            SortedSet<SourceScenario> requiredScenarios) throws IOException {
        SortedMap<PolicyId, PolicyVector> policies = new TreeMap<>();
        SortedMap<PolicyId, RobustPolicySummary> summaries = new TreeMap<>();
        SortedMap<PolicyId, SortedMap<SourceScenario, ScenarioResultStatus>> coverage =
                new TreeMap<>();
        return new OptimizationCorpusView(policies, List.of(), summaries, coverage,
                io.euhedral_execution.training.checkpoint.ArtifactFingerprint.sha256(
                        artifacts.robustRanking().getParent()));
    }

    private OptimizationCorpusReader() {
    }
}
