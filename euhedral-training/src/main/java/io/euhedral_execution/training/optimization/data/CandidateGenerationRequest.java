package io.euhedral_execution.training.optimization.data;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.optimization.PolicyCurvePredictor;
import io.euhedral_execution.training.optimization.config.CandidateGenerationConfig;
import io.euhedral_execution.training.scheduling.data.OptimizationCorpusView;
import java.util.Objects;
import java.util.Set;

public record CandidateGenerationRequest(int iteration, int baseExplorationCount,
        int overflowExplorationCount, int disagreementAuditCount, long sobolCursor,
        long schedulerSeed, OptimizationCorpusView corpus, Set<PolicyId> fixedAnchorIds,
        PolicyCurvePredictor predictor, CandidateGenerationConfig config) {
    public CandidateGenerationRequest {
        Objects.requireNonNull(corpus);
        Objects.requireNonNull(fixedAnchorIds);
        Objects.requireNonNull(predictor);
        Objects.requireNonNull(config);
        fixedAnchorIds = Set.copyOf(fixedAnchorIds);
        if (iteration < 0 || baseExplorationCount < 0 || overflowExplorationCount < 0
                || disagreementAuditCount < 0 || sobolCursor < 0
                || sobolCursor > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid candidate generation request");
        }
    }
}
