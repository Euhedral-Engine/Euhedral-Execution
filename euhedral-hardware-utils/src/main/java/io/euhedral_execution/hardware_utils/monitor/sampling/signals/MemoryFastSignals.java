package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.LongGaugeSignal;
import org.jspecify.annotations.Nullable;

public record MemoryFastSignals(
        @Nullable LongGaugeSignal hardLimitBytes,
        @Nullable LongGaugeSignal highLimitBytes,
        @Nullable LongGaugeSignal usageBytes,
        @Nullable LongGaugeSignal inactiveFileBytes,
        @Nullable CounterSignal cumulativeReclaimBytes,
        @Nullable CounterSignal memoryStallNs) {}
