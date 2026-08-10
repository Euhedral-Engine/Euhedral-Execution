package io.euhedral_execution.training.learning.inputs;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

public record ScenarioTrainingRequest(
        ScenarioInputs inputs,
        SortedSet<SourceScenario> requiredScenarios,
        Path modelDirectory,
        String commitSha,
        boolean dirtyWorkingTree,
        ScenarioTrainingConfig config) {

    public ScenarioTrainingRequest {
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(requiredScenarios);
        Objects.requireNonNull(modelDirectory);
        Objects.requireNonNull(commitSha);
        Objects.requireNonNull(config);
        requiredScenarios = Collections.unmodifiableSortedSet(new TreeSet<>(requiredScenarios));
        if (requiredScenarios.isEmpty()) {
            throw new IllegalArgumentException("Required scenarios must not be empty");
        }
        if (!commitSha.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IllegalArgumentException("Commit SHA must be native Phase 1 form");
        }
        modelDirectory = modelDirectory.toAbsolutePath().normalize();
    }
}
