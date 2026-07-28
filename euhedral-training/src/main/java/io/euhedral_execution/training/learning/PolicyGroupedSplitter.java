package io.euhedral_execution.training.learning;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.data.*;
import java.util.*;

public final class PolicyGroupedSplitter {
    private PolicyGroupedSplitter() {}
    public static PolicyGroupedSplit split(ScenarioLearningTable table, long seed,
            ScenarioTrainingConfig config) {
        TreeMap<PolicyId, LearningPartition> assignments = new TreeMap<>();
        for (ScenarioLearningRow row : table.rows()) {
            assignments.computeIfAbsent(row.policy().id(),
                    id -> partition(id, seed));
        }
        ArrayList<ScenarioLearningRow> train = new ArrayList<>(), validation = new ArrayList<>(),
                test = new ArrayList<>(), earlyRows = new ArrayList<>(), scoreRows = new ArrayList<>();
        TreeSet<PolicyId> early = new TreeSet<>(), score = new TreeSet<>();
        for (var row : table.rows()) switch (assignments.get(row.policy().id())) {
            case TRAIN -> train.add(row);
            case TEST -> test.add(row);
            case VALIDATION -> {
                validation.add(row);
                if (usesAblationEarlyStop(row.policy().id(), seed)) {
                    early.add(row.policy().id());
                    earlyRows.add(row);
                }
                else { score.add(row.policy().id()); scoreRows.add(row); }
            }
        }
        check("train", train, table.requiredScenarios(), config.minimumTrainPolicyGroups(),
                config.minimumTrainRowsPerScenario(), false);
        check("validation", validation, table.requiredScenarios(),
                config.minimumValidationPolicyGroups(), config.minimumValidationRowsPerScenario(), true);
        check("test", test, table.requiredScenarios(), config.minimumTestPolicyGroups(),
                config.minimumTestRowsPerScenario(), true);
        int halfGroups = (config.minimumValidationPolicyGroups() + 1) / 2;
        int halfRows = StrictMath.max(2, (config.minimumValidationRowsPerScenario() + 1) / 2);
        check("ablation early stop", earlyRows, table.requiredScenarios(), halfGroups, halfRows, false);
        check("ablation score", scoreRows, table.requiredScenarios(), halfGroups, halfRows, true);
        return new PolicyGroupedSplit(assignments, train, validation, test, early, score,
                earlyRows, scoreRows);
    }

    static LearningPartition partition(PolicyId policyId, long seed) {
        long hash = HasherApi.getHash(policyId.canonical(), seed);
        int bucket = (int) Math.unsignedMultiplyHigh(hash, 10L);
        return bucket < 8 ? LearningPartition.TRAIN
                : bucket == 8 ? LearningPartition.VALIDATION : LearningPartition.TEST;
    }

    static boolean usesAblationEarlyStop(PolicyId policyId, long seed) {
        long hash = HasherApi.getHash(policyId.canonical(),
                seed ^ 0x9e3779b97f4a7c15L);
        return (hash & 1) == 0;
    }
    public static List<ScenarioLearningRow> withoutScenario(List<ScenarioLearningRow> rows,
            SourceScenario heldOut) {
        return rows.stream().filter(r -> !r.scenario().equals(heldOut)).toList();
    }
    public static List<ScenarioLearningRow> onlyScenario(List<ScenarioLearningRow> rows,
            SourceScenario heldOut) {
        return rows.stream().filter(r -> r.scenario().equals(heldOut)).toList();
    }
    public static List<ScenarioLearningRow> withoutEnvironment(List<ScenarioLearningRow> rows,
            String environment) {
        return rows.stream().filter(r -> !r.scenario().environmentId().equals(environment)).toList();
    }
    private static void check(String name, List<ScenarioLearningRow> rows,
            SortedSet<SourceScenario> scenarios, int groups, int minimumRows, boolean targetChecks) {
        long groupCount = rows.stream().map(r -> r.policy().id()).distinct().count();
        if (groupCount < groups) fail(name + " has too few policy groups");
        for (var scenario : scenarios) {
            List<ScenarioLearningRow> selected = rows.stream()
                    .filter(r -> r.scenario().equals(scenario)).toList();
            if (selected.size() < minimumRows) fail(name + " lacks rows for " + scenario);
            if (targetChecks && (selected.stream().map(ScenarioLearningRow::quality).distinct().count() < 2
                    || selected.stream().noneMatch(r -> r.quality() >= .9)
                    || selected.stream().noneMatch(r -> r.quality() < .9)))
                fail(name + " lacks target variation for " + scenario);
        }
    }
    private static void fail(String message) {
        throw new InsufficientScenarioLearningDataException(message);
    }
}
