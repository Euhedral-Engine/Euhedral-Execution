package io.euhedral_execution.core.utils;

import io.euhedral_execution.hashing.HasherApi;
import java.util.Arrays;

public final class MicroCalibrator {
    private volatile long sink;

    public MicroCalibrator() {}

    /// JIT compiler warmup
    public void warmup() {
        sink = cpuWork(HasherApi.BASE_SEED, 100_000);
    }

    /// Measures the median latency to perform the amount of work
    /// @param cycles amount of work
    /// @return latency in nanoseconds
    public long benchmark(int cycles) {
        long[] times = new long[1001];

        for (int i = 0; i < times.length; i++) {
            times[i] = cpuWork(sink, cycles);
        }
        Arrays.sort(times);
        return times[times.length / 2];
    }

    public long cpuWork(long dummyValue, int cycles) {
        long now = System.nanoTime();

        long value = dummyValue;
        for (int i = 0; i < cycles; i++) {
            value ^= 0x9e3779b97f4a7c15L + i;
            value = Long.rotateLeft(value * 0xbf58476d1ce4e5b9L, 17);
        }

        long elapsed = System.nanoTime() - now;
        this.sink = value;
        return elapsed;
    }
}
