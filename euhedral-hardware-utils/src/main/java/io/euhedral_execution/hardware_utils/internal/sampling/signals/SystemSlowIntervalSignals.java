package io.euhedral_execution.hardware_utils.internal.sampling.signals;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalResolution;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.ResolvedDouble;

public record SystemSlowIntervalSignals(
        ResolvedDouble availableCapacityUnits,
        ResolvedDouble nominalCapacityUnits,
        ThermalSeverity thermalSeverity,
        boolean lowPowerMode,
        long observedAtNs,
        SignalResolution resolution) {}
