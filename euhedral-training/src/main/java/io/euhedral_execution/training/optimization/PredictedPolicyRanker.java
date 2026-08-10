package io.euhedral_execution.training.optimization;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.data.PolicyPredictionCurve;
import io.euhedral_execution.training.optimization.data.PredictedPolicySummary;
import java.util.SortedSet;

public final class PredictedPolicyRanker {
    private PredictedPolicyRanker() {}

    public static PredictedPolicySummary summarize(
            PolicyPredictionCurve curve, SortedSet<SourceScenario> requiredScenarios) {
        return PredictedPolicySummary.from(curve, requiredScenarios);
    }
}
