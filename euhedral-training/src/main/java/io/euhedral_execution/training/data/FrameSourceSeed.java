package io.euhedral_execution.training.data;

public record FrameSourceSeed(int sourceIndex, long idHash, long routingSeed) {
    public FrameSourceSeed {
        if (sourceIndex < 0) {
            throw new IllegalArgumentException("Source index must be non-negative");
        }
    }
}
