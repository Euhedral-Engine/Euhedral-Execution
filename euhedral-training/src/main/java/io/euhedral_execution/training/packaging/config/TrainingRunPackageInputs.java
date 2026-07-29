package io.euhedral_execution.training.packaging.config;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.SourceScenario;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

public record TrainingRunPackageInputs(String packageId, String trainingRunId,
        int checkpointRevision, long schedulerSeed, String commitSha,
        boolean dirtyWorkingTree, BenchmarkExecutionConfig benchmarkConfig,
        SortedSet<SourceScenario> requiredScenarios) {
    public TrainingRunPackageInputs {
        Objects.requireNonNull(packageId);
        Objects.requireNonNull(trainingRunId);
        Objects.requireNonNull(commitSha);
        Objects.requireNonNull(benchmarkConfig);
        Objects.requireNonNull(requiredScenarios);
        requiredScenarios = Collections.unmodifiableSortedSet(new TreeSet<>(requiredScenarios));
        if (!packageId.matches("[a-z0-9][a-z0-9._-]{0,127}")
                || !trainingRunId.matches("[a-z0-9][a-z0-9._-]{0,95}")
                || checkpointRevision <= 0
                || !commitSha.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")
                || requiredScenarios.isEmpty()) {
            throw new IllegalArgumentException("Invalid package inputs");
        }
    }
}
