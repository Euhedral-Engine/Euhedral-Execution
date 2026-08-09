package io.euhedral_execution.hardware_utils.internal.sampling.samples;

import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.LongGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuFastSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.IoFastSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.MemoryFastSignals;
import java.util.BitSet;

public record FastHardwareSample(
    long observedAtNs,
    int logicalSpan,
    UnmodifiableBitSet effectiveCpus,
    LongGaugeSignal quotaCapacityCpus,
    LongGaugeSignal quotaPeriodNs,
    CounterSignal productiveCpuNs,
    CounterSignal scopeQuotaThrottledNs,
    CounterSignal scopeSchedulerWaitNs,
    CounterSignal scopePsiStallNs,
    DoubleGaugeSignal scopeReportedSchedulerStallRatio,
    CpuFastSignals[] cpuSignals,
    MemoryFastSignals memorySignals,
    IoFastSignals ioSignals
) {
    public FastHardwareSample {
        if (logicalSpan <= 0) {
            throw new IllegalArgumentException("Logical span must be positive");
        }
        effectiveCpus = new UnmodifiableBitSet((BitSet) effectiveCpus.clone());
        if (effectiveCpus.length() > logicalSpan) {
            throw new IllegalArgumentException("Effective CPU bit out of span");
        }
        if (cpuSignals == null || cpuSignals.length != logicalSpan) {
            throw new IllegalArgumentException("cpuSignals length must match logicalSpan");
        }
        cpuSignals = cpuSignals.clone();
        
        if (scopeReportedSchedulerStallRatio != null && scopeReportedSchedulerStallRatio.validity() == SignalValidity.VALID) {
            if (scopeReportedSchedulerStallRatio.value() > 1.0) {
                scopeReportedSchedulerStallRatio = new DoubleGaugeSignal(0.0, scopeReportedSchedulerStallRatio.observedAtNs(), SignalValidity.TRANSIENT_FAILURE);
            }
        }
    }
    
    @Override
    public CpuFastSignals[] cpuSignals() {
        return cpuSignals.clone();
    }
}
