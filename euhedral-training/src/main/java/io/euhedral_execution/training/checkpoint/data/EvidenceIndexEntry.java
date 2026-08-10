package io.euhedral_execution.training.checkpoint.data;

import io.euhedral_execution.training.checkpoint.enums.EvidenceSource;
import io.euhedral_execution.training.data.SourceScenario;

public record EvidenceIndexEntry(
        String benchmarkRunId, SourceScenario scenario, ArtifactReference bundle, EvidenceSource source) {
    public EvidenceIndexEntry {
        java.util.Objects.requireNonNull(scenario);
        java.util.Objects.requireNonNull(bundle);
        java.util.Objects.requireNonNull(source);
        if (benchmarkRunId == null || !benchmarkRunId.matches("r1-[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Invalid evidence identity");
        }
    }
}
