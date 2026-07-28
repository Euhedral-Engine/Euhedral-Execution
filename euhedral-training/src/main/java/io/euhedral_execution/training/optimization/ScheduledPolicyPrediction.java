package io.euhedral_execution.training.optimization;

import io.euhedral_execution.training.data.PolicyVector;

public record ScheduledPolicyPrediction(PolicyVector policy,
        PredictedPolicySummary prediction, SchedulePolicyOrigin origin) {
}
