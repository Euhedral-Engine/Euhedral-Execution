package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.data.SourceScenario;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CarryForwardQueue {
    private final List<CarryForwardEntry> entries;

    public CarryForwardQueue(List<CarryForwardEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<CarryForwardEntry> entries() {
        return entries;
    }

    public List<CarryForwardEntry> selectFor(SourceScenario scenario, long iteration, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        List<CarryForwardEntry> candidates = new ArrayList<>();
        for (CarryForwardEntry entry : entries) {
            CarryScenarioState state = entry.scenarioStates().get(scenario);
            if (state != null && state.state() != CoverageState.VALID
                    && iteration >= state.nextEligibleIteration()) {
                candidates.add(entry);
            }
        }
        candidates.sort(priority());
        return candidates.subList(0, Math.min(limit, candidates.size()));
    }

    private static Comparator<CarryForwardEntry> priority() {
        return Comparator.comparingInt(CarryForwardEntry::validScenarioCount).reversed()
                .thenComparing((left, right) -> Double.compare(
                        right.prediction().pessimisticQuality(),
                        left.prediction().pessimisticQuality()))
                .thenComparingDouble(entry -> entry.prediction().maximumEpistemicStdDev())
                .thenComparingDouble(entry -> entry.prediction().maximumDisagreementRange())
                .thenComparingLong(CarryForwardEntry::firstSeenIteration)
                .thenComparing(entry -> entry.policy().id());
    }
}
