package io.euhedral_execution.training.checkpoint;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.scheduling.RunKind;

public record PendingBenchmarkRun(int iteration, RunKind runKind, SourceScenario scenario,
        String benchmarkRunId, String candidateCohortId, ArtifactReference schedule,
        String evidenceRelativePath, PendingRunStatus status) {
    public PendingBenchmarkRun {
        java.util.Objects.requireNonNull(runKind);
        java.util.Objects.requireNonNull(scenario);
        java.util.Objects.requireNonNull(schedule);
        java.util.Objects.requireNonNull(status);
        if (iteration < 0 || benchmarkRunId == null
                || !benchmarkRunId.matches("r1-[0-9a-f]{16}")
                || candidateCohortId == null
                || !candidateCohortId.matches("c1-[0-9a-f]{16}")
                || evidenceRelativePath == null || evidenceRelativePath.indexOf('\\') >= 0
                || ArtifactReference.PathValidator.invalid(evidenceRelativePath)) {
            throw new IllegalArgumentException("Invalid pending benchmark run");
        }
    }
}
