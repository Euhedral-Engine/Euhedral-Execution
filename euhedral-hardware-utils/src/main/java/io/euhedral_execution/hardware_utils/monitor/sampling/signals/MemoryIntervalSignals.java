package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterDelta;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.ResolvedLong;

public record MemoryIntervalSignals(
        ResolvedLong hardLimitBytes,
        ResolvedLong highLimitBytes,
        ResolvedLong usageBytes,
        ResolvedLong inactiveFileBytes,
        CounterDelta cumulativeReclaimBytes,
        CounterDelta memoryStallNs) {}
