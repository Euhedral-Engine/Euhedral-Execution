package io.euhedral_execution.training;

import io.euhedral_execution.training.checkpoint.CheckpointStage;
import io.euhedral_execution.training.data.SourceScenario;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;

public record ClosedLoopResult(CheckpointStage stage, int nextIteration, Path latestCheckpoint,
        Optional<Path> latestMerge, Optional<Path> latestModel,
        SortedSet<SourceScenario> awaitingScenarios) {
}
