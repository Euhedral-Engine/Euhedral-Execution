package io.euhedral_execution.hardware_utils.internal.sampling.samples;

import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuSlowSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.SystemSlowSignals;

public record SlowHardwareSample(
    long observedAtNs,
    int logicalSpan,
    CpuSlowSignals[] cpuSignals,
    SystemSlowSignals systemSignals
) {
    public SlowHardwareSample {
        if (logicalSpan <= 0) {
            throw new IllegalArgumentException("Logical span must be positive");
        }
        if (cpuSignals == null || cpuSignals.length != logicalSpan) {
            throw new IllegalArgumentException("cpuSignals length must match logicalSpan");
        }
        cpuSignals = cpuSignals.clone();
    }
    
    @Override
    public CpuSlowSignals[] cpuSignals() {
        return cpuSignals.clone();
    }
}
