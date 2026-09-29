package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalResolution;
import io.euhedral_execution.hardware_utils.monitor.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.ResolvedDouble;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.ResolvedLong;

public record CpuSlowIntervalSignals(
        ResolvedDouble availableCapacityUnits,
        ResolvedDouble nominalCapacityUnits,
        ResolvedLong constrainedFrequencyHz,
        ResolvedLong nominalFrequencyHz,
        ThermalSeverity thermalSeverity,
        boolean lowPowerMode,
        long observedAtNs,
        SignalResolution resolution) {}
