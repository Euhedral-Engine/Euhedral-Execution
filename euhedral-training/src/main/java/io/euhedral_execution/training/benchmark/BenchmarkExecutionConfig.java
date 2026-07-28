package io.euhedral_execution.training.benchmark;

public record BenchmarkExecutionConfig(int expectedRepetitions, long sampleDurationNanos,
        long livenessTimeoutNanos, int framesPerSource, long resetTimeoutNanos,
        boolean orderedFrames) {
    public BenchmarkExecutionConfig {
        if (expectedRepetitions <= 0 || sampleDurationNanos <= 0 || livenessTimeoutNanos <= 0
                || framesPerSource <= 0 || resetTimeoutNanos <= 0) {
            throw new IllegalArgumentException("Invalid benchmark execution config");
        }
    }

    public static BenchmarkExecutionConfig defaults() {
        return new BenchmarkExecutionConfig(10, 200_000_000L, 50_000_000L, 100_000,
                2_000_000_000L, false);
    }
}
