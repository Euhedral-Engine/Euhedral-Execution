package io.euhedral_execution.training.data;

import java.util.List;
import java.util.Objects;

public record BenchmarkParameters(
        int expectedRepetitions,
        long sampleDurationNanos,
        long livenessTimeoutNanos,
        int framesPerSource,
        long resetTimeoutNanos,
        boolean orderedFrames,
        String cpuSetHex,
        List<FrameSourceSeed> frameSourceSeeds) {

    public BenchmarkParameters {
        Objects.requireNonNull(cpuSetHex);
        frameSourceSeeds = List.copyOf(frameSourceSeeds);
        if (expectedRepetitions < 1
                || expectedRepetitions > 999_999
                || sampleDurationNanos <= 0
                || livenessTimeoutNanos <= 0
                || framesPerSource <= 0
                || resetTimeoutNanos <= 0
                || !cpuSetHex.matches("[0-9a-f]+(?:,[0-9a-f]{8})*")) {
            throw new IllegalArgumentException("Invalid benchmark parameters");
        }
        for (int i = 0; i < frameSourceSeeds.size(); i++) {
            if (frameSourceSeeds.get(i).sourceIndex() != i) {
                throw new IllegalArgumentException("Source seeds must be contiguous");
            }
        }
    }
}
