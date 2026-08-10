package io.euhedral_execution.training.optimization.data;

import java.util.List;

public record CandidateGenerationResult(
        List<PredictedCandidate> disagreementAudits,
        List<PredictedCandidate> baseExploration,
        List<PredictedCandidate> overflowExploration,
        long nextSobolCursor,
        int cmaAssigned,
        int scoreBandAssigned,
        int directSobolAssigned,
        int auditShortfall) {
    public CandidateGenerationResult {
        disagreementAudits = List.copyOf(disagreementAudits);
        baseExploration = List.copyOf(baseExploration);
        overflowExploration = List.copyOf(overflowExploration);
        if (nextSobolCursor < 0
                || cmaAssigned < 0
                || scoreBandAssigned < 0
                || directSobolAssigned < 0
                || auditShortfall < 0) {
            throw new IllegalArgumentException("Invalid candidate generation result");
        }
    }
}
