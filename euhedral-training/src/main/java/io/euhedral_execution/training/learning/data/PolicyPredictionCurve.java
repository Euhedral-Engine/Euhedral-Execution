package io.euhedral_execution.training.learning.data;

import java.util.List;
import java.util.Objects;

import io.euhedral_execution.training.data.PolicyVector;

public record PolicyPredictionCurve(PolicyVector policy, List<ScenarioPrediction> scenarios) {

    public PolicyPredictionCurve {
        Objects.requireNonNull(policy);
        scenarios = List.copyOf(scenarios);
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException();
        }
        for (int i = 1; i < scenarios.size(); i++) {
            if (scenarios.get(i - 1).scenario().compareTo(scenarios.get(i).scenario()) >= 0) {
                throw new IllegalArgumentException("Scenarios must be unique and sorted");
            }
        }
    }
}
