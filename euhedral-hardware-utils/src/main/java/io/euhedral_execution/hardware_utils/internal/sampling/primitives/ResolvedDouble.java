package io.euhedral_execution.hardware_utils.internal.sampling.primitives;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalResolution;

/// Immutable resolved double gauge value for one evaluation cycle.
///
/// Units: value is in the unit declared by the containing field.
///   observedAtNs is the monotonic nanosecond timestamp of the last valid observation.
///
/// Resolution rules:
///   CURRENT   -- the value is from a fresh valid gauge reading this evaluation.
///   CACHED    -- the value is from a prior valid reading still within its TTL.
///   UNAVAILABLE -- no usable value; value is canonical 0.0.
///   (BASELINE is not used for gauges.)
///
/// -0.0 is canonicalized to +0.0 by the compact constructor.
public record ResolvedDouble(double value, long observedAtNs, SignalResolution resolution) {
    public ResolvedDouble {
        if (resolution == SignalResolution.UNAVAILABLE) {
            value = 0.0;
        } else if (value == 0.0) {
            value = 0.0;
        }
    }
}
