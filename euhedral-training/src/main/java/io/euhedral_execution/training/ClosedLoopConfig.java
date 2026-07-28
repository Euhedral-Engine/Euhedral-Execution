package io.euhedral_execution.training;

import io.euhedral_execution.training.benchmark.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.ScenarioTrainingConfig;
import io.euhedral_execution.training.merge.*;
import io.euhedral_execution.training.optimization.CandidateGenerationConfig;
import io.euhedral_execution.training.scheduling.CandidateBudgetConfig;
import java.nio.file.Path;
import java.util.*;

public record ClosedLoopConfig(Path workspace, String trainingRunId, int iterations,
        int candidateBudget, SortedSet<SourceScenario> requiredScenarios,
        String activeEnvironmentId, int scenariosPerIteration, long schedulerSeed,
        long initialSobolCursor, Optional<Path> bootstrapPolicies,
        Optional<Path> initialCalibrationPlan, List<Path> initialObservationBundles,
        Map<SourceScenario, String> referenceOverrides, String commitSha, boolean dirtyWorkingTree,
        CandidateBudgetConfig budgetConfig, CandidateGenerationConfig generationConfig,
        BenchmarkExecutionConfig benchmarkConfig, AnchorSelectionConfig anchorSelectionConfig,
        CalibrationConfig calibrationConfig, AggregationConfig aggregationConfig,
        ScenarioTrainingConfig trainingConfig, boolean resume) {
    public ClosedLoopConfig {
        requiredScenarios = java.util.Collections.unmodifiableSortedSet(
                new java.util.TreeSet<>(requiredScenarios));
        initialObservationBundles = List.copyOf(initialObservationBundles);
        referenceOverrides = Map.copyOf(referenceOverrides);
    }
}
