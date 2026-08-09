package io.euhedral_execution.hardware_utils.internal.sampling.signals;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalResolution;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.ResolvedDouble;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.ResolvedLong;

public record CpuSlowIntervalSignals(
    ResolvedDouble availableCapacityUnits,
    ResolvedDouble nominalCapacityUnits,
    ResolvedLong constrainedFrequencyHz,
    ResolvedLong nominalFrequencyHz,
    ThermalSeverity thermalSeverity,
    boolean lowPowerMode,
    long observedAtNs,
    SignalResolution resolution
) {
}
