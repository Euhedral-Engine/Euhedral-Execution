package io.euhedral_execution.training.benchmark.data;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.BenchmarkParameters;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.SourceScenario;
import java.nio.file.Path;
import java.util.List;

/// Defines one native benchmark run and its closed-loop progress context.
public record NativeBenchmarkRunPlan(
        String trainingRunId,
        int iteration,
        int totalIterations,
        String benchmarkRunId,
        String candidateCohortId,
        SourceScenario scenario,
        List<ScheduledPolicy> policies,
        BenchmarkExecutionConfig executionConfig,
        BenchmarkParameters parameters,
        long schedulerSeed,
        String commitSha,
        boolean dirtyWorkingTree,
        Path outputBundle) {
    public NativeBenchmarkRunPlan {
        if (iteration < 0 || totalIterations <= 0 || iteration > totalIterations) {
            throw new IllegalArgumentException("Invalid closed-loop iteration range");
        }
        policies = List.copyOf(policies);
        outputBundle = outputBundle.toAbsolutePath().normalize();
    }
}
