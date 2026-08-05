package io.euhedral_execution.hardware_utils.internal.sampling.primitives;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;

public record BooleanSignal(boolean value, long observedAtNs, SignalValidity validity) {
    public BooleanSignal {
        if (validity != SignalValidity.VALID) {
            value = false;
        }
    }
}
