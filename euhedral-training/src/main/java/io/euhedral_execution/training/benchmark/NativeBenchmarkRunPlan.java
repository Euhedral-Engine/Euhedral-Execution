package io.euhedral_execution.training.benchmark;

import io.euhedral_execution.training.data.BenchmarkParameters;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.SourceScenario;
import java.nio.file.Path;
import java.util.List;

public record NativeBenchmarkRunPlan(String trainingRunId, int iteration, String benchmarkRunId,
        String candidateCohortId, SourceScenario scenario, List<ScheduledPolicy> policies,
        BenchmarkExecutionConfig executionConfig, BenchmarkParameters parameters,
        String commitSha, boolean dirtyWorkingTree, Path outputBundle) {
    public NativeBenchmarkRunPlan {
        policies = List.copyOf(policies);
        outputBundle = outputBundle.toAbsolutePath().normalize();
    }
}
