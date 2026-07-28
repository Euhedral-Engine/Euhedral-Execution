package io.euhedral_execution.training.optimization;

import io.euhedral_execution.training.data.ScheduledPolicy;

public record ScheduledPolicyPrediction(ScheduledPolicy scheduledPolicy,
        PredictedPolicySummary prediction, SchedulePolicyOrigin origin) {
}
