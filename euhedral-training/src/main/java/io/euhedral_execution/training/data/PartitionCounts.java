package io.euhedral_execution.training.data;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

public record PartitionCounts(SortedMap<String, Integer> policyCounts,
                              SortedMap<String, Integer> rowCounts,
                              SortedMap<String, SortedMap<SourceScenario, Integer>> scenarioRowCounts) {

    private static final Set<String> PARTITIONS =
            Set.of("TRAIN", "VALIDATION", "TEST", "ABLATION_EARLY_STOP", "ABLATION_SCORE");

    private static SortedMap<String, Integer> immutableCounts(Map<String, Integer> source) {
        TreeMap<String, Integer> copy = new TreeMap<>(source);
        if (copy.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("Invalid partition count");
        }
        return Collections.unmodifiableSortedMap(copy);
    }

    public PartitionCounts {
        Objects.requireNonNull(policyCounts);
        Objects.requireNonNull(rowCounts);
        Objects.requireNonNull(scenarioRowCounts);
        policyCounts = immutableCounts(policyCounts);
        rowCounts = immutableCounts(rowCounts);
        TreeMap<String, SortedMap<SourceScenario, Integer>> nested = new TreeMap<>();
        for (Map.Entry<String, SortedMap<SourceScenario, Integer>> entry : scenarioRowCounts.entrySet()) {
            TreeMap<SourceScenario, Integer> values = new TreeMap<>(entry.getValue());
            if (values.values().stream().anyMatch(value -> value == null || value < 0)) {
                throw new IllegalArgumentException("Invalid scenario partition count");
            }
            nested.put(entry.getKey(), Collections.unmodifiableSortedMap(values));
        }
        scenarioRowCounts = Collections.unmodifiableSortedMap(nested);
        if (!policyCounts.keySet().equals(PARTITIONS) || !rowCounts.keySet().equals(PARTITIONS)
                || !scenarioRowCounts.keySet().equals(PARTITIONS)) {
            throw new IllegalArgumentException("Partition count keys disagree");
        }
        var scenarioCatalog = scenarioRowCounts.get("TRAIN").keySet();
        if (scenarioCatalog.isEmpty()) {
            throw new IllegalArgumentException("Scenario partition counts are empty");
        }
        for (String partition : PARTITIONS) {
            SortedMap<SourceScenario, Integer> counts = scenarioRowCounts.get(partition);
            int rows = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (!counts.keySet().equals(scenarioCatalog) || rows != rowCounts.get(partition)
                    || policyCounts.get(partition) > rows
                    || rows > 0 && policyCounts.get(partition) == 0) {
                throw new IllegalArgumentException("Partition counts disagree");
            }
        }
        if (policyCounts.get("VALIDATION")
                != policyCounts.get("ABLATION_EARLY_STOP") + policyCounts.get("ABLATION_SCORE")
                || rowCounts.get("VALIDATION")
                != rowCounts.get("ABLATION_EARLY_STOP") + rowCounts.get("ABLATION_SCORE")) {
            throw new IllegalArgumentException("Validation ablation counts disagree");
        }
        for (SourceScenario scenario : scenarioCatalog) {
            if (scenarioRowCounts.get("VALIDATION").get(scenario)
                    != scenarioRowCounts.get("ABLATION_EARLY_STOP").get(scenario)
                    + scenarioRowCounts.get("ABLATION_SCORE").get(scenario)) {
                throw new IllegalArgumentException("Validation ablation scenario counts disagree");
            }
        }
    }
}
