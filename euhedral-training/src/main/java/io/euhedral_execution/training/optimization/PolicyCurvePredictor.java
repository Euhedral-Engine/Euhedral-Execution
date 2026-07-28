package io.euhedral_execution.training.optimization;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.learning.PolicyPredictionCurve;
import java.util.List;

@FunctionalInterface
public interface PolicyCurvePredictor {
    List<PolicyPredictionCurve> predictConfiguredCurves(List<PolicyVector> policies) throws Exception;
}
