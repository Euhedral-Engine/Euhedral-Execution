package io.euhedral_execution.hardware_utils.monitor.sampling.samples;

import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterDelta;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.ResolvedDouble;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.ResolvedLong;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.CpuIntervalSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.CpuSlowIntervalSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.IoIntervalSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.MemoryIntervalSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.SystemSlowIntervalSignals;
import io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet;
import java.util.BitSet;

public record IntervalHardwareSample(
        long observedAtNs,
        int logicalSpan,
        UnmodifiableBitSet effectiveCpus,
        ResolvedLong quotaCapacityCpus,
        ResolvedLong quotaPeriodNs,
        CounterDelta productiveCpuNs,
        CounterDelta scopeQuotaThrottledNs,
        CounterDelta scopeSchedulerWaitNs,
        CounterDelta scopePsiStallNs,
        ResolvedDouble scopeReportedSchedulerStallRatio,
        CpuIntervalSignals[] cpuSignals,
        MemoryIntervalSignals memorySignals,
        IoIntervalSignals ioSignals,
        CpuSlowIntervalSignals[] cpuSlowSignals,
        SystemSlowIntervalSignals systemSlowSignals) {
    public IntervalHardwareSample {
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

        if (cpuSlowSignals == null || cpuSlowSignals.length != logicalSpan) {
            throw new IllegalArgumentException("cpuSlowSignals length must match logicalSpan");
        }
        cpuSlowSignals = cpuSlowSignals.clone();
    }

    @Override
    public CpuIntervalSignals[] cpuSignals() {
        return cpuSignals.clone();
    }

    @Override
    public CpuSlowIntervalSignals[] cpuSlowSignals() {
        return cpuSlowSignals.clone();
    }
}
