package io.euhedral_execution.hardware_utils.internal.sampling.primitives;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;

/// Immutable primitive cumulative counter signal from a hardware or OS source.
///
/// Units: value is the cumulative counter in the unit declared by the containing field
///   (e.g., nanoseconds of CPU time, bytes of I/O). observedAtNs is a monotonic
///   System.nanoTime nanosecond timestamp.
///
/// Validity rules:
///   VALID            -- value is in [0, Long.MAX_VALUE]; the signal was successfully read.
///   TRANSIENT_FAILURE -- this read attempt failed; payload is canonical 0. Retry may succeed.
///   UNSUPPORTED      -- the platform cannot supply this counter; payload is canonical 0.
///                       Set only by the platform adapter; never inferred from a single failure.
///
/// Compact constructor enforces:
///   Non-VALID => value = 0L.
///   VALID with value < 0 => validity promoted to TRANSIENT_FAILURE and value = 0L.
public record CounterSignal(long value, long observedAtNs, SignalValidity validity) {
    public CounterSignal {
        if (validity != SignalValidity.VALID) {
            value = 0L;
        } else if (value < 0L) {
            validity = SignalValidity.TRANSIENT_FAILURE;
            value = 0L;
        }
    }

    /// Factory: valid counter with the given nonnegative cumulative value.
    public static CounterSignal valid(long value, long observedAtNs) {
        return new CounterSignal(value, observedAtNs, SignalValidity.VALID);
    }

    /// Factory: transient failure at the given attempt timestamp; payload is 0.
    public static CounterSignal transientFailure(long observedAtNs) {
        return new CounterSignal(0L, observedAtNs, SignalValidity.TRANSIENT_FAILURE);
    }

    /// Factory: permanently unsupported at the given requested timestamp; payload is 0.
    public static CounterSignal unsupported(long requestedAtNs) {
        return new CounterSignal(0L, requestedAtNs, SignalValidity.UNSUPPORTED);
    }
}
