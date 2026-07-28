package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyRole;
import io.euhedral_execution.training.learning.ScenarioPrediction;
import io.euhedral_execution.training.merge.MergeRecords.ScenarioResultStatus;
import io.euhedral_execution.training.optimization.PredictedPolicySummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
            CarryScenarioState state = entry.scenarios().get(scenario);
            if (state != null && state.coverage() != CoverageState.VALID
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
        TreeMap<PolicyId, CarryForwardEntry> working = new TreeMap<>();
        previous.forEach(entry -> working.put(entry.policy().id(), entry));
        Map<PolicyId, PredictedPolicySummary> predictions = completedSchedule.selectedPredictions()
                .stream().collect(java.util.stream.Collectors.toMap(
                        prediction -> prediction.policy().id(),
                        io.euhedral_execution.training.optimization.ScheduledPolicyPrediction::prediction,
                        (left, right) -> left));
        for (PolicyId admitted : completedSchedule.carryAdmissions()) {
            if (working.containsKey(admitted)) {
                continue;
            }
            var vector = corpus.policies().get(admitted);
            PredictedPolicySummary prediction = predictions.get(admitted);
            if (vector == null || prediction == null) {
                throw new IllegalArgumentException("Carry admission lacks policy or prediction");
            }
            working.put(admitted, new CarryForwardEntry(vector, completedIteration,
                    completedIteration, scenarioGrid(prediction, corpus, admitted)));
        }
        TreeMap<SourceScenario, java.util.Set<PolicyId>> attempted = new TreeMap<>();
        for (ScheduledRun run : completedSchedule.runs()) {
            java.util.Set<PolicyId> ids = new java.util.TreeSet<>();
            for (var policy : run.policies()) {
                if (policy.roles().contains(PolicyRole.CARRY_FORWARD)
                        || policy.roles().contains(PolicyRole.EXPLORATION)
                        || policy.roles().contains(PolicyRole.DISAGREEMENT_AUDIT)) {
                    ids.add(policy.policy().id());
                }
            }
            attempted.put(run.scenario(), ids);
        }
        ArrayList<CarryForwardEntry> result = new ArrayList<>();
        for (CarryForwardEntry entry : working.values()) {
            var summary = corpus.summaries().get(entry.policy().id());
            if (summary != null && summary.eligible()) {
                continue;
            }
            TreeMap<SourceScenario, CarryScenarioState> states =
                    new TreeMap<>(entry.scenarios());
            for (var stateEntry : states.entrySet()) {
                SourceScenario scenario = stateEntry.getKey();
                CarryScenarioState state = stateEntry.getValue();
                CoverageState coverage = coverage(corpus, entry.policy().id(), scenario);
                CarryScenarioState updated = new CarryScenarioState(scenario, coverage,
                        state.attemptCount(), state.lastAttemptIteration(),
                        coverage == CoverageState.VALID ? 0 : state.nextEligibleIteration(),
                        state.prediction());
                if (attempted.getOrDefault(scenario, java.util.Set.of())
                        .contains(entry.policy().id())) {
                    updated = updated.attempted(completedIteration, coverage);
                }
                states.put(scenario, updated);
            }
            result.add(new CarryForwardEntry(entry.policy(), entry.firstSeenIteration(),
                    completedIteration, states));
        }
        return List.copyOf(result);
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
        return entries.stream().map(entry -> {
            PredictedPolicySummary summary = predictions.get(entry.policy().id());
            if (summary == null || summary.predictions().size() != entry.scenarios().size()) {
                throw new IllegalArgumentException("Carry rescore lacks a complete curve");
            }
            TreeMap<SourceScenario, CarryScenarioState> states = new TreeMap<>();
            for (ScenarioPrediction prediction : summary.predictions()) {
                CarryScenarioState old = entry.scenarios().get(prediction.scenario());
                if (old == null) {
                    throw new IllegalArgumentException("Carry rescore scenario mismatch");
                }
                states.put(prediction.scenario(), new CarryScenarioState(prediction.scenario(),
                        old.coverage(), old.attemptCount(), old.lastAttemptIteration(),
                        old.nextEligibleIteration(), prediction));
            }
            return new CarryForwardEntry(entry.policy(), entry.firstSeenIteration(), iteration,
                    states);
        }).toList();
    }

    public static List<CarryForwardEntry> selectForScenario(List<CarryForwardEntry> entries,
            SourceScenario scenario, int iteration, int limit) {
        return new CarryForwardQueue(entries).selectFor(scenario, iteration, limit);
    }

    private static Comparator<CarryForwardEntry> priority() {
        return Comparator.comparingInt(CarryForwardEntry::validScenarioCount).reversed()
                .thenComparing((left, right) -> Double.compare(
                        pessimisticMissing(right), pessimisticMissing(left)))
                .thenComparingDouble(CarryForwardQueue::maximumMissingEpistemic)
                .thenComparingDouble(CarryForwardQueue::maximumMissingDisagreement)
                .thenComparingInt(CarryForwardEntry::firstSeenIteration)
                .thenComparing(entry -> entry.policy().id());
    }

    private static SortedMapBuilder scenarioGrid(PredictedPolicySummary prediction,
            OptimizationCorpusView corpus, PolicyId policyId) {
        SortedMapBuilder builder = new SortedMapBuilder();
        for (ScenarioPrediction row : prediction.predictions()) {
            builder.put(row.scenario(), new CarryScenarioState(row.scenario(),
                    coverage(corpus, policyId, row.scenario()), 0,
                    java.util.OptionalInt.empty(), 0, row));
        }
        return builder;
    }

    private static CoverageState coverage(OptimizationCorpusView corpus, PolicyId policyId,
            SourceScenario scenario) {
        ScenarioResultStatus status = corpus.coverage().getOrDefault(policyId,
                java.util.Collections.emptySortedMap()).get(scenario);
        if (status == ScenarioResultStatus.VALID_STRONG
                || status == ScenarioResultStatus.VALID_WEAK_OVERRIDE) {
            return CoverageState.VALID;
        }
        return status == null || status == ScenarioResultStatus.MISSING
                ? CoverageState.MISSING : CoverageState.REJECTED;
    }

    private static double pessimisticMissing(CarryForwardEntry entry) {
        return entry.scenarios().values().stream()
                .filter(state -> state.coverage() != CoverageState.VALID)
                .mapToDouble(state -> state.prediction().qualityIntervalLow())
                .min().orElse(1.0);
    }

    private static double maximumMissingEpistemic(CarryForwardEntry entry) {
        return entry.scenarios().values().stream()
                .filter(state -> state.coverage() != CoverageState.VALID)
                .mapToDouble(state -> state.prediction().epistemicStdDev())
                .max().orElse(0.0);
    }

    private static double maximumMissingDisagreement(CarryForwardEntry entry) {
        return entry.scenarios().values().stream()
                .filter(state -> state.coverage() != CoverageState.VALID)
                .mapToDouble(state -> state.prediction().disagreementRange())
                .max().orElse(0.0);
    }

    private static final class SortedMapBuilder extends TreeMap<SourceScenario,
            CarryScenarioState> {
    }
}
