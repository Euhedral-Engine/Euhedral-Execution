package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalResolution;
import io.euhedral_execution.hardware_utils.monitor.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.ResolvedDouble;

public record SystemSlowIntervalSignals(
        ResolvedDouble availableCapacityUnits,
        ResolvedDouble nominalCapacityUnits,
        ThermalSeverity thermalSeverity,
        boolean lowPowerMode,
        long observedAtNs,
        SignalResolution resolution) {}
