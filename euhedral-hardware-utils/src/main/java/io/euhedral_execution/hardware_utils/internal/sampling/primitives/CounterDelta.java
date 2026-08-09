package io.euhedral_execution.hardware_utils.internal.sampling.primitives;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalResolution;

/// Immutable resolved counter interval for one evaluation cycle.
///
/// Units:
///   delta     -- nonnegative raw counter increment (same unit as the source counter)
///   elapsedNs -- strictly positive wall-clock nanoseconds for CURRENT or CACHED;
///                zero for BASELINE or UNAVAILABLE
///   observedAtNs -- monotonic nanosecond timestamp of the current observation
///
/// Validity rules:
///   CURRENT   -- delta is the difference between consecutive valid cumulative counter
///                readings; elapsedNs is strictly positive.
///   CACHED    -- delta and elapsedNs are retained from the last CURRENT interval
///                while the raw signal is transiently absent but within its TTL.
///   BASELINE  -- a first, reset, wrap, or regressed counter; delta and elapsedNs
///                are zero; the engine has stored (c, tc) as the new baseline.
///   UNAVAILABLE -- unsupported, expired, or inconsistent; delta and elapsedNs are zero.
///
/// Compact constructor enforces:
///   BASELINE/UNAVAILABLE  => delta = 0, elapsedNs = 0
///   CURRENT/CACHED        => elapsedNs must be strictly positive (IllegalArgumentException)
///   delta must be nonnegative for CURRENT/CACHED
public record CounterDelta(long delta, long elapsedNs, long observedAtNs, SignalResolution resolution) {
    public CounterDelta {
        if (resolution == SignalResolution.BASELINE || resolution == SignalResolution.UNAVAILABLE) {
            delta = 0L;
            elapsedNs = 0L;
        } else {
            if (elapsedNs <= 0) {
                throw new IllegalArgumentException(
                    "CounterDelta elapsedNs must be > 0 for CURRENT/CACHED; got " + elapsedNs);
            }
            if (delta < 0) {
                throw new IllegalArgumentException(
                    "CounterDelta delta must be >= 0; got " + delta);
            }
        }
    }
}
