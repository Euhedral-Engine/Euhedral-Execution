package io.euhedral_execution.training.checkpoint;

import io.euhedral_execution.training.data.SourceScenario;

public record EvidenceIndexEntry(String benchmarkRunId, SourceScenario scenario,
        ArtifactReference bundle, EvidenceSource source) {
}
