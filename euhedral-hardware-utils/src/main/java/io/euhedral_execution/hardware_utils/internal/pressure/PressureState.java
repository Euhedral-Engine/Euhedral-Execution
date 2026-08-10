package io.euhedral_execution.hardware_utils.internal.pressure;

import java.util.Arrays;

public final class PressureState {

    // Offsets for global/shared cells
    static final int CELL_SCOPE_WAIT = 0;
    static final int CELL_SCOPE_PSI = 1;
    static final int CELL_SCOPE_REPORTED = 2;
    static final int CELL_GLOBAL_THROTTLE = 3;
    static final int CELL_SYSTEM_THERMAL = 4;
    static final int CELL_SYSTEM_LOW_POWER = 5;
    static final int CELL_HEADROOM = 6;
    static final int CELL_RECLAIM = 7;
    static final int CELL_MEMORY_STALL = 8;
    static final int CELL_IO_STALL = 9;
    static final int CELL_IO_LATENCY = 10;
    static final int CELL_IO_QUEUE = 11;
    static final int GLOBAL_CELL_COUNT = 12;

    // Per-CPU offsets within a CPU block
    static final int PC_WAIT = 0;
    static final int PC_PSI = 1;
    static final int PC_REPORTED = 2;
    static final int PC_RUN_QUEUE = 3;
    static final int PC_CPU_THROTTLE = 4;
    static final int PC_STEAL = 5;
    static final int PC_EXTERNAL = 6;
    static final int PC_CAPACITY_LOSS = 7;
    static final int PC_FREQUENCY_LOSS = 8;
    static final int PC_THERMAL = 9;
    static final int PC_LOW_POWER = 10;
    static final int PER_CPU_CELL_COUNT = 11;
    final int logicalSpan;
    final boolean[] initialized;
    final double[] previous;
    final long[] lastEvaluationNs;

    public PressureState(int logicalSpan) {
        if (logicalSpan <= 0) {
            throw new IllegalArgumentException("logicalSpan must be positive");
        }
        this.logicalSpan = logicalSpan;
        int totalCells = GLOBAL_CELL_COUNT + (logicalSpan * PER_CPU_CELL_COUNT);
        this.initialized = new boolean[totalCells];
        this.previous = new double[totalCells];
        this.lastEvaluationNs = new long[totalCells];
    }

    private PressureState(int logicalSpan, boolean[] initialized, double[] previous, long[] lastEvaluationNs) {
        this.logicalSpan = logicalSpan;
        this.initialized = initialized.clone();
        this.previous = previous.clone();
        this.lastEvaluationNs = lastEvaluationNs.clone();
    }

    int cpuCellIndex(int cpuId, int perCpuOffset) {
        return GLOBAL_CELL_COUNT + (cpuId * PER_CPU_CELL_COUNT) + perCpuOffset;
    }

    PressureState deepCopy() {
        return new PressureState(logicalSpan, initialized, previous, lastEvaluationNs);
    }

    void clear() {
        Arrays.fill(initialized, false);
        Arrays.fill(previous, 0.0);
        Arrays.fill(lastEvaluationNs, 0L);
    }

    void clearCpu(int cpuId) {
        int base = GLOBAL_CELL_COUNT + (cpuId * PER_CPU_CELL_COUNT);
        for (int i = 0; i < PER_CPU_CELL_COUNT; i++) {
            initialized[base + i] = false;
            previous[base + i] = 0.0;
            lastEvaluationNs[base + i] = 0L;
        }
    }
}
