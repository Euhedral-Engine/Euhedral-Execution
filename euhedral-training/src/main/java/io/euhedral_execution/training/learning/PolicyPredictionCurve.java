package io.euhedral_execution.training.learning;
import io.euhedral_execution.training.data.PolicyVector;
import java.util.*;
public record PolicyPredictionCurve(PolicyVector policy, List<ScenarioPrediction> scenarios) {
    public PolicyPredictionCurve {
        Objects.requireNonNull(policy); scenarios = List.copyOf(scenarios);
        if (scenarios.isEmpty()) throw new IllegalArgumentException();
        for (int i = 1; i < scenarios.size(); i++)
            if (scenarios.get(i - 1).scenario().compareTo(scenarios.get(i).scenario()) >= 0)
                throw new IllegalArgumentException("Scenarios must be unique and sorted");
    }
}
