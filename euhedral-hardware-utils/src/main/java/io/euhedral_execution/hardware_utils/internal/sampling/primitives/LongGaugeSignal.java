package io.euhedral_execution.hardware_utils.internal.sampling.primitives;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;

public record LongGaugeSignal(long value, long observedAtNs, SignalValidity validity) {
    public LongGaugeSignal {
        if (validity != SignalValidity.VALID) {
            value = 0L;
        } else if (value < 0L) {
            validity = SignalValidity.TRANSIENT_FAILURE;
            value = 0L;
        }
    }

    public static LongGaugeSignal valid(long value, long observedAtNs) {
        return new LongGaugeSignal(value, observedAtNs, SignalValidity.VALID);
    }

    public static LongGaugeSignal transientFailure(long observedAtNs) {
        return new LongGaugeSignal(0L, observedAtNs, SignalValidity.TRANSIENT_FAILURE);
    }

    public static LongGaugeSignal unsupported(long requestedAtNs) {
        return new LongGaugeSignal(0L, requestedAtNs, SignalValidity.UNSUPPORTED);
    }
}
