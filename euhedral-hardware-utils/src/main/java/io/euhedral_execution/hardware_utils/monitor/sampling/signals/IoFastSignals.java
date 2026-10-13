package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.DoubleGaugeSignal;
import org.jspecify.annotations.Nullable;

public record IoFastSignals(
        @Nullable CounterSignal productiveBytes,
        @Nullable CounterSignal stallNs,
        @Nullable CounterSignal operationLatencyNs,
        @Nullable CounterSignal completedOperations,
        @Nullable DoubleGaugeSignal maximumQueueDepth) {}
