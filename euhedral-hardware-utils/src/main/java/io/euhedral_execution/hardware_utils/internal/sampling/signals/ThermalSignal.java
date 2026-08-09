package io.euhedral_execution.hardware_utils.internal.sampling.signals;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.ThermalSeverity;
import java.util.Objects;

public record ThermalSignal(ThermalSeverity value, long observedAtNs, SignalValidity validity) {
    public ThermalSignal {
        Objects.requireNonNull(value, "value");
        if (validity != SignalValidity.VALID) {
            value = ThermalSeverity.NOMINAL;
        }
    }
}
