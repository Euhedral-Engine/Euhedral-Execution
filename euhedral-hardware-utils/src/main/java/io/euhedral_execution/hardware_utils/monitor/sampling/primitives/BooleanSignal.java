package io.euhedral_execution.hardware_utils.monitor.sampling.primitives;

import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalValidity;

public record BooleanSignal(boolean value, long observedAtNs, SignalValidity validity) {
    public BooleanSignal {
        if (validity != SignalValidity.VALID) {
            value = false;
        }
    }
}
