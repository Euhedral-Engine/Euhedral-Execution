package io.euhedral_execution.hardware_utils.internal.sampling.signals;

import io.euhedral_execution.hardware_utils.internal.sampling.primitives.BooleanSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.LongGaugeSignal;

public record CpuSlowSignals(
        DoubleGaugeSignal availableCapacityUnits,
        DoubleGaugeSignal nominalCapacityUnits,
        LongGaugeSignal constrainedFrequencyHz,
        LongGaugeSignal nominalFrequencyHz,
        ThermalSignal thermalSeverity,
        BooleanSignal lowPowerMode) {}
