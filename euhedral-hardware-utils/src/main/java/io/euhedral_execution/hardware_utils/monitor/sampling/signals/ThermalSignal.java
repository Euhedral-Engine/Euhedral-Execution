package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.monitor.sampling.enums.ThermalSeverity;
import java.util.Objects;

public record ThermalSignal(ThermalSeverity value, long observedAtNs, SignalValidity validity) {
    public ThermalSignal {
        Objects.requireNonNull(value, "value");
        if (validity != SignalValidity.VALID) {
            value = ThermalSeverity.NOMINAL;
        }
    }
}
