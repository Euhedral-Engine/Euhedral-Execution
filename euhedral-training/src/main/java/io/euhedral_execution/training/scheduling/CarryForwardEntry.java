package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.optimization.PredictedPolicySummary;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

public record CarryForwardEntry(PolicyVector policy, long firstSeenIteration,
        PredictedPolicySummary prediction,
        SortedMap<SourceScenario, CarryScenarioState> scenarioStates) {
    public CarryForwardEntry {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(prediction);
        Objects.requireNonNull(scenarioStates);
        if (firstSeenIteration < 0) {
            throw new IllegalArgumentException("firstSeenIteration must not be negative");
        }
        TreeMap<SourceScenario, CarryScenarioState> copy = new TreeMap<>(scenarioStates);
        for (var entry : copy.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().scenario())) {
                throw new IllegalArgumentException("Carry scenario key must match its state");
            }
        }
        scenarioStates = Collections.unmodifiableSortedMap(copy);
        if (scenarioStates.isEmpty()) {
            throw new IllegalArgumentException("Invalid carry scenario grid");
        }
    }

    public int validScenarioCount() {
        int count = 0;
        for (CarryScenarioState state : scenarioStates.values()) {
            if (state.state() == CoverageState.VALID) {
                count++;
            }
        }
        return count;
    }
}
