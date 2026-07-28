package io.euhedral_execution.training.checkpoint;

import java.nio.file.Path;

public record LoadedCheckpoint(Path snapshotDirectory, ClosedLoopCheckpoint checkpoint) {
}
