package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.BooleanSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.DoubleGaugeSignal;

public record SystemSlowSignals(
        DoubleGaugeSignal availableCapacityUnits,
        DoubleGaugeSignal nominalCapacityUnits,
        ThermalSignal thermalSeverity,
        BooleanSignal lowPowerMode) {}
