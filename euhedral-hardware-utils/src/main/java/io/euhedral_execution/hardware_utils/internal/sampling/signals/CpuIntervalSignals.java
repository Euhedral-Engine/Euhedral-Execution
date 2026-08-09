package io.euhedral_execution.hardware_utils.internal.sampling.signals;

import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterDelta;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.ResolvedDouble;

public record CpuIntervalSignals(
    CounterDelta schedulerWait,
    CounterDelta psiStall,
    ResolvedDouble reportedSchedulerStallRatio,
    CounterDelta quotaThrottle,
    CounterDelta steal,
    ResolvedDouble externalContentionRatio,
    ResolvedDouble runnablePerCapacity
) {
}
