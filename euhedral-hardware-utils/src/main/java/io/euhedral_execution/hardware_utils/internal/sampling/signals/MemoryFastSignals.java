package io.euhedral_execution.hardware_utils.internal.sampling.signals;

import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.LongGaugeSignal;

public record MemoryFastSignals(
        LongGaugeSignal hardLimitBytes,
        LongGaugeSignal highLimitBytes,
        LongGaugeSignal usageBytes,
        LongGaugeSignal inactiveFileBytes,
        CounterSignal cumulativeReclaimBytes,
        CounterSignal memoryStallNs) {}
