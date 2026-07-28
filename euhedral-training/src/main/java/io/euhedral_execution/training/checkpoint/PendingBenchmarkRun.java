package io.euhedral_execution.training.checkpoint;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.scheduling.RunKind;

public record PendingBenchmarkRun(int iteration, RunKind runKind, SourceScenario scenario,
        String benchmarkRunId, String candidateCohortId, ArtifactReference schedule,
        String evidenceRelativePath, PendingRunStatus status) {
}
