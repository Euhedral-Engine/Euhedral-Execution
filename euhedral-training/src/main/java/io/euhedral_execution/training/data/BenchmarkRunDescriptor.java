package io.euhedral_execution.training.data;

import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import java.time.Instant;
import java.util.Objects;

public record BenchmarkRunDescriptor(
        int schemaVersion,
        String benchmarkRunId,
        int closedLoopIteration,
        String candidateCohortId,
        SourceScenario scenario,
        String commitSha,
        boolean dirtyWorkingTree,
        EvidenceOrigin evidenceOrigin,
        Instant startedAt,
        BenchmarkParameters parameters) {

    private static boolean validId(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9._-]{0,95}");
    }

    public BenchmarkRunDescriptor {
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(evidenceOrigin);
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(parameters);
        if (schemaVersion != 1 || !validId(benchmarkRunId) || !validId(candidateCohortId)
                || closedLoopIteration < 0
                || parameters.frameSourceSeeds().size() != scenario.sourceCount()) {
            throw new IllegalArgumentException("Invalid run descriptor");
        }
        if (evidenceOrigin == EvidenceOrigin.NATIVE
                && (commitSha == null || !commitSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Native evidence requires a commit SHA");
        }
        if (commitSha == null || commitSha.isBlank()) {
            throw new IllegalArgumentException("Commit SHA is required");
        }
    }
}
