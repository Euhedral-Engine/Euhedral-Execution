package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

public record CarryForwardEntry(PolicyVector policy, int firstSeenIteration,
        int lastUpdatedIteration, SortedMap<SourceScenario, CarryScenarioState> scenarios) {
    public CarryForwardEntry {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(scenarios);
        if (firstSeenIteration < 0 || lastUpdatedIteration < firstSeenIteration) {
            throw new IllegalArgumentException("Invalid carry iteration range");
        }
        TreeMap<SourceScenario, CarryScenarioState> copy = new TreeMap<>(scenarios);
        for (var entry : copy.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().scenario())) {
                throw new IllegalArgumentException("Carry scenario key must match its state");
            }
        }
        scenarios = Collections.unmodifiableSortedMap(copy);
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("Invalid carry scenario grid");
        }
    }

    public int validScenarioCount() {
        int count = 0;
        for (CarryScenarioState state : scenarios.values()) {
            if (state.coverage() == CoverageState.VALID) {
                count++;
            }
        }
        return count;
    }
}
