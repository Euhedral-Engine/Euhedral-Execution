package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.LongGaugeSignal;

public record MemoryFastSignals(
        LongGaugeSignal hardLimitBytes,
        LongGaugeSignal highLimitBytes,
        LongGaugeSignal usageBytes,
        LongGaugeSignal inactiveFileBytes,
        CounterSignal cumulativeReclaimBytes,
        CounterSignal memoryStallNs) {}
