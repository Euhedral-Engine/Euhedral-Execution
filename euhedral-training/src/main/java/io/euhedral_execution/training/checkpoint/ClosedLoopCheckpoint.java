package io.euhedral_execution.training.checkpoint;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.scheduling.CarryForwardEntry;
import io.euhedral_execution.training.scheduling.RotationGroup;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public record ClosedLoopCheckpoint(int schemaVersion, String trainingRunId, int revision,
        CheckpointStage stage, int nextIteration, long sobolCursor, String configSha256,
        SortedSet<SourceScenario> requiredScenarios,
        SortedMap<RotationGroup, Integer> rotationCursors, List<EvidenceIndexEntry> evidence,
        List<CarryForwardEntry> carryForward, Optional<String> anchorSetId,
        Optional<ArtifactReference> calibrationPlan, Optional<ArtifactReference> latestMerge,
        Optional<ArtifactReference> latestModel, Optional<ArtifactReference> pendingSchedule,
        List<PendingBenchmarkRun> pendingRuns) {
    public ClosedLoopCheckpoint {
        requiredScenarios = java.util.Collections.unmodifiableSortedSet(
                new TreeSet<>(requiredScenarios));
        rotationCursors = java.util.Collections.unmodifiableSortedMap(
                new TreeMap<>(rotationCursors));
        evidence = List.copyOf(evidence);
        carryForward = List.copyOf(carryForward);
        pendingRuns = List.copyOf(pendingRuns);
        if (schemaVersion != 1 || revision < 0 || nextIteration < 0 || sobolCursor < 0
                || configSha256 == null || !configSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid checkpoint");
        }
    }
}
