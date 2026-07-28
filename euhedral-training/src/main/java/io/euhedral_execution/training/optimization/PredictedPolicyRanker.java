package io.euhedral_execution.training.optimization;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.PolicyPredictionCurve;
import java.util.SortedSet;

public final class PredictedPolicyRanker {
    public static PredictedPolicySummary summarize(PolicyPredictionCurve curve,
            SortedSet<SourceScenario> requiredScenarios) {
        return PredictedPolicySummary.from(curve, requiredScenarios);
    }

    private PredictedPolicyRanker() {
    }
}
