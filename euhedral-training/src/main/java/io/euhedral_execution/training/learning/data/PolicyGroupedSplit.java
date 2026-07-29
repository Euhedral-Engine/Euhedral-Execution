package io.euhedral_execution.training.learning.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.learning.enums.LearningPartition;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningRow;

public record PolicyGroupedSplit(SortedMap<PolicyId, LearningPartition> policyPartitions,
                                 List<ScenarioLearningRow> trainingRows,
                                 List<ScenarioLearningRow> validationRows,
                                 List<ScenarioLearningRow> testRows,
                                 SortedSet<PolicyId> ablationEarlyStopPolicies,
                                 SortedSet<PolicyId> ablationScorePolicies,
                                 List<ScenarioLearningRow> ablationEarlyStopRows,
                                 List<ScenarioLearningRow> ablationScoreRows) {

    private static void validateRows(List<ScenarioLearningRow> rows, LearningPartition expected,
            SortedMap<PolicyId, LearningPartition> partitions, Set<PolicyId> represented) {
        for (int index = 0; index < rows.size(); index++) {
            ScenarioLearningRow row = rows.get(index);
            if (partitions.get(row.policy().id()) != expected
                    || index > 0 && rows.get(index - 1).compareTo(row) >= 0) {
                throw new IllegalArgumentException("Invalid partition rows");
            }
            represented.add(row.policy().id());
        }
    }

    private static TreeSet<PolicyId> ids(List<ScenarioLearningRow> rows) {
        TreeSet<PolicyId> result = new TreeSet<>();
        for (ScenarioLearningRow row : rows) {
            result.add(row.policy().id());
        }
        return result;
    }

    public PolicyGroupedSplit {
        policyPartitions = Collections.unmodifiableSortedMap(new TreeMap<>(policyPartitions));
        trainingRows = List.copyOf(trainingRows);
        validationRows = List.copyOf(validationRows);
        testRows = List.copyOf(testRows);
        ablationEarlyStopPolicies =
                Collections.unmodifiableSortedSet(new TreeSet<>(ablationEarlyStopPolicies));
        ablationScorePolicies =
                Collections.unmodifiableSortedSet(new TreeSet<>(ablationScorePolicies));
        ablationEarlyStopRows = List.copyOf(ablationEarlyStopRows);
        ablationScoreRows = List.copyOf(ablationScoreRows);
        TreeSet<PolicyId> represented = new TreeSet<>();
        validateRows(trainingRows, LearningPartition.TRAIN, policyPartitions, represented);
        validateRows(validationRows, LearningPartition.VALIDATION, policyPartitions, represented);
        validateRows(testRows, LearningPartition.TEST, policyPartitions, represented);
        if (!represented.equals(policyPartitions.keySet()) || !Collections.disjoint(
                ablationEarlyStopPolicies, ablationScorePolicies)) {
            throw new IllegalArgumentException("Policy partition assignments disagree");
        }
        TreeSet<PolicyId> validationPolicies = ids(validationRows);
        TreeSet<PolicyId> ablationPolicies = new TreeSet<>(ablationEarlyStopPolicies);
        ablationPolicies.addAll(ablationScorePolicies);
        if (!validationPolicies.equals(ablationPolicies) || !ids(ablationEarlyStopRows).equals(
                ablationEarlyStopPolicies) || !ids(ablationScoreRows).equals(
                ablationScorePolicies)) {
            throw new IllegalArgumentException("Validation ablation assignments disagree");
        }
        ArrayList<ScenarioLearningRow> ablationRows = new ArrayList<>(ablationEarlyStopRows);
        ablationRows.addAll(ablationScoreRows);
        ablationRows.sort(null);
        if (!ablationRows.equals(validationRows)) {
            throw new IllegalArgumentException("Validation ablation rows disagree");
        }
    }
}
