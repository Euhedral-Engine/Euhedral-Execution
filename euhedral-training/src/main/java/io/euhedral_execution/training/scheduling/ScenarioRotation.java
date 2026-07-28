package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.data.SourceScenario;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

public final class ScenarioRotation {
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
