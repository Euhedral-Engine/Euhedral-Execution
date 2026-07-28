package io.euhedral_execution.training.optimization;

import io.euhedral_execution.training.data.PolicyVector;
import java.util.List;

@FunctionalInterface
public interface PolicyCurvePredictor {
    List<PredictedPolicySummary> predict(List<PolicyVector> policies);
}
