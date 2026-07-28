package io.euhedral_execution.training;

import io.euhedral_execution.training.checkpoint.*;
import io.euhedral_execution.training.scheduling.RotationGroup;
import java.nio.file.Path;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ClosedLoopRunner {
    public static final class StopRequested extends RuntimeException {
        private StopRequested() {
            super(null, null, false, false);
        }
    }

    static StopRequested stopSignal() {
        return new StopRequested();
    }

    public static ClosedLoopResult run(ClosedLoopConfig config) throws Exception {
        try (WorkspaceLock ignored = WorkspaceLock.acquire(config.workspace())) {
            String configHash = "0".repeat(64);
            Optional<LoadedCheckpoint> loaded = CheckpointSnapshotCodec.loadLatest(
                    config.workspace(), config.trainingRunId(), configHash);
            int revision = loaded.map(value -> value.checkpoint().revision() + 1).orElse(1);
            CheckpointStage stage = config.iterations() == 0 ? CheckpointStage.RUN_COMPLETE
                    : CheckpointStage.READY_TO_TRAIN;
            ClosedLoopCheckpoint checkpoint = new ClosedLoopCheckpoint(1, config.trainingRunId(),
                    revision, stage, loaded.map(value -> value.checkpoint().nextIteration())
                    .orElse(1), config.initialSobolCursor(), configHash, config.requiredScenarios(),
                    new TreeMap<>(), java.util.List.of(), java.util.List.of(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    java.util.List.of());
            LoadedCheckpoint written = CheckpointSnapshotCodec.writeNext(config.workspace(),
                    checkpoint);
            return new ClosedLoopResult(stage, checkpoint.nextIteration(), written.snapshotDirectory(),
                    Optional.empty(), Optional.empty(), new TreeSet<>());
        }
    }

    /** ROBUST_OPTIMIZER_PHASE5_CONFIG transitional adapter. */
    public static ClosedLoopResult run() throws Exception {
        throw new IllegalArgumentException(
                "Phase 3 closed-loop requires a typed ClosedLoopConfig");
    }

    private ClosedLoopRunner() {
    }
}
