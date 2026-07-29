package io.euhedral_execution.training.optimization.data;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.optimization.enums.CandidateOrigin;

public record PredictedCandidate(PolicyVector policy, PredictedPolicySummary prediction,
        CandidateOrigin origin) {
}
