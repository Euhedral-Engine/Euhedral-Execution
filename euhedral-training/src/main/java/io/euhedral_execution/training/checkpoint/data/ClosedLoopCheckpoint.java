package io.euhedral_execution.training.checkpoint.data;

import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.checkpoint.enums.PendingRunStatus;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.scheduling.data.CarryForwardEntry;
import io.euhedral_execution.training.scheduling.data.RotationGroup;
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
        evidence = evidence.stream().sorted(java.util.Comparator.comparing(
                EvidenceIndexEntry::benchmarkRunId)).toList();
        carryForward = carryForward.stream().sorted(java.util.Comparator.comparing(entry ->
                entry.policy().id())).toList();
        pendingRuns = pendingRuns.stream().sorted(java.util.Comparator.comparing(
                PendingBenchmarkRun::scenario)).toList();
        if (schemaVersion != 1 || revision < 0 || nextIteration < 0 || sobolCursor < 0
                || trainingRunId == null
                || !trainingRunId.matches("[a-z0-9][a-z0-9._-]{0,95}")
                || configSha256 == null || !configSha256.matches("[0-9a-f]{64}")
                || requiredScenarios.isEmpty()) {
            throw new IllegalArgumentException("Invalid checkpoint");
        }
        java.util.TreeSet<RotationGroup> expectedGroups = requiredScenarios.stream()
                .map(scenario -> new RotationGroup(scenario.environmentId(),
                        scenario.availablePhysicalCoreCount()))
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        if (!rotationCursors.keySet().equals(expectedGroups)) {
            throw new IllegalArgumentException("Rotation groups disagree with scenario catalog");
        }
        for (var entry : rotationCursors.entrySet()) {
            long count = requiredScenarios.stream().filter(scenario ->
                    scenario.environmentId().equals(entry.getKey().environmentId())
                            && scenario.availablePhysicalCoreCount()
                            == entry.getKey().availablePhysicalCoreCount()).count();
            if (entry.getValue() < 0 || entry.getValue() >= count) {
                throw new IllegalArgumentException("Invalid rotation cursor");
            }
        }
        if (evidence.stream().map(EvidenceIndexEntry::benchmarkRunId).distinct().count()
                != evidence.size()
                || pendingRuns.stream().map(PendingBenchmarkRun::benchmarkRunId).distinct().count()
                != pendingRuns.size()) {
            throw new IllegalArgumentException("Duplicate checkpoint run identity");
        }
        for (CarryForwardEntry entry : carryForward) {
            if (!entry.scenarios().keySet().equals(requiredScenarios)
                    || entry.validScenarioCount() == requiredScenarios.size()) {
                throw new IllegalArgumentException("Invalid carry scenario grid");
            }
        }
        java.util.Map<String, EvidenceIndexEntry> evidenceByRun = evidence.stream().collect(
                java.util.stream.Collectors.toMap(EvidenceIndexEntry::benchmarkRunId,
                        java.util.function.Function.identity()));
        for (PendingBenchmarkRun pending : pendingRuns) {
            if (!requiredScenarios.contains(pending.scenario())
                    || pendingSchedule.isPresent()
                    && !pending.schedule().equals(pendingSchedule.orElseThrow())
                    || pending.status() == PendingRunStatus.COMPLETE
                    && (!evidenceByRun.containsKey(pending.benchmarkRunId())
                    || !evidenceByRun.get(pending.benchmarkRunId()).scenario()
                    .equals(pending.scenario())
                    || !evidenceByRun.get(pending.benchmarkRunId()).bundle().relativePath()
                    .equals(pending.evidenceRelativePath()))) {
                throw new IllegalArgumentException("Pending/evidence index disagreement");
            }
        }
        boolean calibrated = stage != CheckpointStage.BOOTSTRAP_PENDING;
        if (calibrated && (anchorSetId.isEmpty() || calibrationPlan.isEmpty()
                || latestMerge.isEmpty())) {
            throw new IllegalArgumentException("Calibrated stage lacks Phase 1 artifacts");
        }
        boolean modelRequired = stage == CheckpointStage.MODEL_READY
                || stage == CheckpointStage.MODEL_REJECTED
                || stage == CheckpointStage.SCHEDULE_READY
                || stage == CheckpointStage.BENCHMARKING
                || stage == CheckpointStage.READY_TO_MERGE
                || stage == CheckpointStage.RUN_COMPLETE
                || stage == CheckpointStage.READY_TO_TRAIN && nextIteration > 1;
        if (modelRequired && latestModel.isEmpty()) {
            throw new IllegalArgumentException("Checkpoint stage lacks model artifact");
        }
        boolean scheduleRequired = stage == CheckpointStage.SCHEDULE_READY
                || stage == CheckpointStage.BENCHMARKING
                || stage == CheckpointStage.READY_TO_MERGE;
        if (scheduleRequired != pendingSchedule.isPresent()
                || scheduleRequired && pendingRuns.isEmpty()) {
            throw new IllegalArgumentException("Checkpoint stage/schedule disagreement");
        }
    }
}
