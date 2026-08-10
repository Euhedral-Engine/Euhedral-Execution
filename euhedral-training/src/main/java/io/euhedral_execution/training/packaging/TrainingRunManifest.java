package io.euhedral_execution.training.packaging;

import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.enums.CalibrationAcceptance;
import io.euhedral_execution.training.packaging.enums.TrainingRunPackageStatus;
import java.util.List;

record TrainingRunManifest(
        String packageId,
        String trainingRunId,
        int checkpointRevision,
        CheckpointStage checkpointStage,
        TrainingRunPackageStatus status,
        boolean runComplete,
        String configSha256,
        String checkpointSha256,
        String commitSha,
        boolean dirtyWorkingTree,
        List<SourceScenario> requiredScenarios,
        CalibrationAcceptance calibrationAcceptance,
        List<String> winningPolicyIds,
        List<PackageFile> files,
        List<PackageOmission> omissions) {
    TrainingRunManifest {
        requiredScenarios = List.copyOf(requiredScenarios);
        winningPolicyIds = List.copyOf(winningPolicyIds);
        files = List.copyOf(files);
        omissions = List.copyOf(omissions);
        if (checkpointRevision <= 0
                || !configSha256.matches("[0-9a-f]{64}")
                || !checkpointSha256.matches("[0-9a-f]{64}")
                || !commitSha.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")
                || winningPolicyIds.size() > 10
                || !files.equals(files.stream()
                        .sorted(java.util.Comparator.comparing(PackageFile::path))
                        .toList())
                || !omissions.equals(omissions.stream().sorted().toList())
                || runComplete != (status == TrainingRunPackageStatus.COMPLETE)
                || runComplete != (checkpointStage == CheckpointStage.RUN_COMPLETE)
                || runComplete && !omissions.isEmpty()) {
            throw new IllegalArgumentException("Invalid package manifest");
        }
    }
}
