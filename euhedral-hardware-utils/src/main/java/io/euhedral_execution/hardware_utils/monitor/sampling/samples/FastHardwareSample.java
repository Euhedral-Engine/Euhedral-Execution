package io.euhedral_execution.hardware_utils.monitor.sampling.samples;

import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.LongGaugeSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.CpuFastSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.IoFastSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.MemoryFastSignals;
import io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet;
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
        IoFastSignals ioSignals) {
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

        if (scopeReportedSchedulerStallRatio != null
                && scopeReportedSchedulerStallRatio.validity() == SignalValidity.VALID) {
            if (scopeReportedSchedulerStallRatio.value() > 1.0) {
                scopeReportedSchedulerStallRatio = new DoubleGaugeSignal(
                        0.0, scopeReportedSchedulerStallRatio.observedAtNs(), SignalValidity.TRANSIENT_FAILURE);
            }
        }
    }

    @Override
    public CpuFastSignals[] cpuSignals() {
        return cpuSignals.clone();
    }
}
