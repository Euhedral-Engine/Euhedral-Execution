package io.euhedral_execution.training.optimization;

import io.euhedral_execution.training.data.PolicyVector;

public record PredictedCandidate(PolicyVector policy, PredictedPolicySummary prediction,
        CandidateOrigin origin) {
}
