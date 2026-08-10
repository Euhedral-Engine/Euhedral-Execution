package io.euhedral_execution.training.optimization.data;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.optimization.enums.SchedulePolicyOrigin;

public record ScheduledPolicyPrediction(
        PolicyVector policy, PredictedPolicySummary prediction, SchedulePolicyOrigin origin) {}
