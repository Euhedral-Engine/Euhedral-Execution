package io.euhedral_execution.hardware_utils.internal.sampling.signals;

import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.DoubleGaugeSignal;

public record IoFastSignals(
        CounterSignal productiveBytes,
        CounterSignal stallNs,
        CounterSignal operationLatencyNs,
        CounterSignal completedOperations,
        DoubleGaugeSignal maximumQueueDepth) {}
