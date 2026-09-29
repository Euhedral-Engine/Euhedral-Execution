package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterDelta;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.ResolvedDouble;

public record CpuIntervalSignals(
        CounterDelta schedulerWait,
        CounterDelta psiStall,
        ResolvedDouble reportedSchedulerStallRatio,
        CounterDelta quotaThrottle,
        CounterDelta steal,
        ResolvedDouble externalContentionRatio,
        ResolvedDouble runnablePerCapacity) {}
