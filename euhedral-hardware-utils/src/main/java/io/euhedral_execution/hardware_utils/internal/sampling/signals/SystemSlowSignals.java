package io.euhedral_execution.hardware_utils.internal.sampling.signals;

import io.euhedral_execution.hardware_utils.internal.sampling.primitives.BooleanSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.DoubleGaugeSignal;

public record SystemSlowSignals(
    DoubleGaugeSignal availableCapacityUnits,
    DoubleGaugeSignal nominalCapacityUnits,
    ThermalSignal thermalSeverity,
    BooleanSignal lowPowerMode
) {
}
