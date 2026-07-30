package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.scheduling.data.RotationGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

public final class ScenarioRotation {
    public static List<SourceScenario> select(SortedSet<SourceScenario> requiredScenarios,
            java.util.SortedMap<RotationGroup, Integer> cursors, String activeEnvironmentId,
            int activeCoreCount, int scenariosPerIteration) {
        RotationGroup group = new RotationGroup(activeEnvironmentId, activeCoreCount);
        return new ScenarioRotation().select(requiredScenarios, group,
                cursors.getOrDefault(group, 0), scenariosPerIteration);
    }

    public static java.util.SortedMap<RotationGroup, Integer> advance(
            SortedSet<SourceScenario> requiredScenarios,
            java.util.SortedMap<RotationGroup, Integer> cursors,
            List<SourceScenario> completedSelection) {
        java.util.TreeMap<RotationGroup, Integer> next = new java.util.TreeMap<>(cursors);
        if (completedSelection.isEmpty()) {
            return next;
        }
        SourceScenario first = completedSelection.getFirst();
        RotationGroup group = new RotationGroup(first.environmentId(),
                first.availablePhysicalCoreCount());
        long runnable = requiredScenarios.stream()
                .filter(scenario -> scenario.environmentId().equals(group.environmentId()))
                .filter(scenario -> scenario.availablePhysicalCoreCount()
                        == group.availablePhysicalCoreCount())
                .count();
        next.put(group, new ScenarioRotation().advance(cursors.getOrDefault(group, 0),
                completedSelection.size(), Math.toIntExact(runnable)));
        return java.util.Collections.unmodifiableSortedMap(next);
    }

    public List<SourceScenario> select(SortedSet<SourceScenario> required, RotationGroup group,
            int nextIndex, int scenariosPerIteration) {
        if (nextIndex < 0 || scenariosPerIteration <= 0) {
            throw new IllegalArgumentException("Invalid rotation cursor");
        }
        List<SourceScenario> runnable = required.stream()
                .filter(scenario -> scenario.environmentId().equals(group.environmentId()))
                .filter(scenario -> scenario.availablePhysicalCoreCount()
                        == group.availablePhysicalCoreCount())
                .toList();
        if (runnable.isEmpty()) {
            throw new IllegalArgumentException("No required scenario is runnable for "
                    + group.canonical());
        }
        int count = Math.min(scenariosPerIteration, runnable.size());
        ArrayList<SourceScenario> selected = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            selected.add(runnable.get((nextIndex + i) % runnable.size()));
        }
        return List.copyOf(selected);
    }

    public int advance(int nextIndex, int selectedCount, int runnableCount) {
        if (nextIndex < 0 || selectedCount < 0 || runnableCount <= 0) {
            throw new IllegalArgumentException("Invalid rotation advance");
        }
        return (nextIndex + selectedCount) % runnableCount;
    }
}
