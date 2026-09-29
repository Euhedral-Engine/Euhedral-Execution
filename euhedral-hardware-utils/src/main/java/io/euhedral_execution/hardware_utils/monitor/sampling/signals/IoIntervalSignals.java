package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterDelta;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.LatencyInterval;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.ResolvedDouble;

public record IoIntervalSignals(
        CounterDelta productiveBytes,
        CounterDelta stallNs,
        LatencyInterval operationsLatency,
        ResolvedDouble maximumQueueDepth) {}
