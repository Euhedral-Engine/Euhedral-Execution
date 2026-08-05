package io.euhedral_execution.hardware_utils.internal.sampling.signals;

import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterDelta;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.LatencyInterval;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.ResolvedDouble;

public record IoIntervalSignals(
    CounterDelta productiveBytes,
    CounterDelta stallNs,
    LatencyInterval operationsLatency,
    ResolvedDouble maximumQueueDepth
) {
}
