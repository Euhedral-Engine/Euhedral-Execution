package io.euhedral_execution.hardware_utils.internal.sampling.primitives;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;

/// Immutable primitive double gauge signal carrying a physical measurement value.
///
/// Units: value is in the unit declared by the containing field (e.g., ratio, CPU count).
///   observedAtNs is a monotonic System.nanoTime nanosecond timestamp.
///
/// Validity rules:
///   VALID            -- value is finite, nonnegative, and semantically valid for
///                       the field's domain; -0.0 is canonicalized to +0.0.
///   TRANSIENT_FAILURE -- this attempt failed; payload is canonical 0.0.
///   UNSUPPORTED      -- the platform cannot supply this signal; payload is canonical 0.0.
///                       Never inferred from a single failed attempt; set only by the
///                       platform adapter.
///
/// Out-of-range ratio enforcement (values that must lie in [0.0, 1.0]) is the
///   responsibility of the group compact constructor (CpuFastSignals, FastHardwareSample).
///   Unnormalized non-negative scalars (runnablePerCapacity, maximumQueueDepth) may exceed 1.0.
public record DoubleGaugeSignal(double value, long observedAtNs, SignalValidity validity) {
    public DoubleGaugeSignal {
        if (validity != SignalValidity.VALID) {
            value = 0.0;
        } else if (!Double.isFinite(value) || value < 0.0) {
            validity = SignalValidity.TRANSIENT_FAILURE;
            value = 0.0;
        } else if (value == 0.0) {
            value = 0.0;
        }
    }

    /// Factory: valid signal with the given finite nonnegative value.
    public static DoubleGaugeSignal valid(double value, long observedAtNs) {
        return new DoubleGaugeSignal(value, observedAtNs, SignalValidity.VALID);
    }

    /// Factory: transient failure at the given attempt timestamp; payload is 0.0.
    public static DoubleGaugeSignal transientFailure(long observedAtNs) {
        return new DoubleGaugeSignal(0.0, observedAtNs, SignalValidity.TRANSIENT_FAILURE);
    }

    /// Factory: permanently unsupported at the given requested timestamp; payload is 0.0.
    public static DoubleGaugeSignal unsupported(long requestedAtNs) {
        return new DoubleGaugeSignal(0.0, requestedAtNs, SignalValidity.UNSUPPORTED);
    }
}
