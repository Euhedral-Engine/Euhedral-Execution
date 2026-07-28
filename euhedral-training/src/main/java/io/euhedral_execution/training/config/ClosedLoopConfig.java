package io.euhedral_execution.training.config;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.config.AnchorSelectionConfig;
import io.euhedral_execution.training.merge.config.CalibrationConfig;
import io.euhedral_execution.training.optimization.config.CandidateGenerationConfig;
import io.euhedral_execution.training.scheduling.config.CandidateBudgetConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;

public record ClosedLoopConfig(Path workspace, String trainingRunId, int iterations,
        int candidateBudget, SortedSet<SourceScenario> requiredScenarios,
        String activeEnvironmentId, int scenariosPerIteration, long schedulerSeed,
        long initialSobolCursor, Optional<Path> bootstrapPolicies,
        Optional<Path> initialCalibrationPlan, List<Path> initialObservationBundles,
        Map<SourceScenario, String> referenceOverrides, String commitSha, boolean dirtyWorkingTree,
        CandidateBudgetConfig budgetConfig, CandidateGenerationConfig generationConfig,
        BenchmarkExecutionConfig benchmarkConfig, AnchorSelectionConfig anchorSelectionConfig,
        CalibrationConfig calibrationConfig, AggregationConfig aggregationConfig,
        ScenarioTrainingConfig trainingConfig, boolean resume, Path stopFile) {
    public ClosedLoopConfig {
        Objects.requireNonNull(workspace);
        Objects.requireNonNull(trainingRunId);
        Objects.requireNonNull(requiredScenarios);
        Objects.requireNonNull(activeEnvironmentId);
        Objects.requireNonNull(bootstrapPolicies);
        Objects.requireNonNull(initialCalibrationPlan);
        Objects.requireNonNull(initialObservationBundles);
        Objects.requireNonNull(referenceOverrides);
        Objects.requireNonNull(commitSha);
        Objects.requireNonNull(budgetConfig);
        Objects.requireNonNull(generationConfig);
        Objects.requireNonNull(benchmarkConfig);
        Objects.requireNonNull(anchorSelectionConfig);
        Objects.requireNonNull(calibrationConfig);
        Objects.requireNonNull(aggregationConfig);
        Objects.requireNonNull(trainingConfig);
        Objects.requireNonNull(stopFile);
        workspace = workspace.toAbsolutePath().normalize();
        stopFile = stopFile.toAbsolutePath().normalize();
        requiredScenarios = java.util.Collections.unmodifiableSortedSet(
                new java.util.TreeSet<>(requiredScenarios));
        initialObservationBundles = List.copyOf(initialObservationBundles);
        referenceOverrides = Map.copyOf(referenceOverrides);
        if (!trainingRunId.matches("[a-z0-9][a-z0-9._-]{0,95}")
                || iterations <= 0 || candidateBudget <= 0
                || requiredScenarios.isEmpty() || scenariosPerIteration <= 0
                || initialSobolCursor < 0
                || !activeEnvironmentId.matches("[a-z0-9][a-z0-9._-]{0,63}")
                || !commitSha.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")
                || requiredScenarios.stream().noneMatch(scenario ->
                scenario.environmentId().equals(activeEnvironmentId))
                || !requiredScenarios.containsAll(referenceOverrides.keySet())
                || !initialObservationBundles.isEmpty() && initialCalibrationPlan.isEmpty()
                || bootstrapPolicies.isPresent() && initialCalibrationPlan.isPresent()) {
            throw new IllegalArgumentException("Invalid closed-loop configuration");
        }
    }
}
