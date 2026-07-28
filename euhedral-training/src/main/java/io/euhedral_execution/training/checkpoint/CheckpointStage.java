package io.euhedral_execution.training.checkpoint;

public enum CheckpointStage {
    BOOTSTRAP_PENDING,
    READY_TO_TRAIN,
    MODEL_READY,
    MODEL_REJECTED,
    SCHEDULE_READY,
    BENCHMARKING,
    READY_TO_MERGE,
    RUN_COMPLETE
}
