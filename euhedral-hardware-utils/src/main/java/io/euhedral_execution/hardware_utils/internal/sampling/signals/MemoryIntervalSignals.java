package io.euhedral_execution.hardware_utils.internal.sampling.signals;

import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterDelta;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.ResolvedLong;

public record MemoryIntervalSignals(
    ResolvedLong hardLimitBytes,
    ResolvedLong highLimitBytes,
    ResolvedLong usageBytes,
    ResolvedLong inactiveFileBytes,
    CounterDelta cumulativeReclaimBytes,
    CounterDelta memoryStallNs
) {
}
