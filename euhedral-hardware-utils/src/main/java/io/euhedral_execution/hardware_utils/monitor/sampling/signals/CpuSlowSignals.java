package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.BooleanSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.LongGaugeSignal;

public record CpuSlowSignals(
        DoubleGaugeSignal availableCapacityUnits,
        DoubleGaugeSignal nominalCapacityUnits,
        LongGaugeSignal constrainedFrequencyHz,
        LongGaugeSignal nominalFrequencyHz,
        ThermalSignal thermalSeverity,
        BooleanSignal lowPowerMode) {}
