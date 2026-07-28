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

    public static List<CarryForwardEntry> reconcile(List<CarryForwardEntry> previous,
            OptimizationCorpusView corpus, IterationSchedule completedSchedule,
            int completedIteration) {
        return previous.stream()
                .filter(entry -> {
                    var summary = corpus.summaries().get(entry.policy().id());
                    return summary == null || !summary.eligible();
                })
                .map(entry -> new CarryForwardEntry(entry.policy(), entry.firstSeenIteration(),
                        entry.prediction(), entry.scenarioStates()))
                .toList();
    }

    public static List<CarryForwardEntry> rescore(List<CarryForwardEntry> entries,
            io.euhedral_execution.training.optimization.PolicyCurvePredictor predictor,
            int iteration) {
        java.util.Map<io.euhedral_execution.training.data.PolicyId,
                io.euhedral_execution.training.optimization.PredictedPolicySummary> predictions =
                predictor.predict(entries.stream().map(CarryForwardEntry::policy).toList())
                        .stream().collect(java.util.stream.Collectors.toMap(
                                summary -> summary.policy().id(),
                                java.util.function.Function.identity()));
        return entries.stream().map(entry -> new CarryForwardEntry(entry.policy(),
                entry.firstSeenIteration(), predictions.get(entry.policy().id()),
                entry.scenarioStates())).toList();
    }

    public static List<CarryForwardEntry> selectForScenario(List<CarryForwardEntry> entries,
            SourceScenario scenario, int iteration, int limit) {
        return new CarryForwardQueue(entries).selectFor(scenario, iteration, limit);
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
