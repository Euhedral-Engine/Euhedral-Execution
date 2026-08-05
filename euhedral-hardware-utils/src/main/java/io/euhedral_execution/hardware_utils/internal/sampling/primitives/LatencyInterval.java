package io.euhedral_execution.hardware_utils.internal.sampling.primitives;

import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalResolution;

/// Immutable resolved latency interval pairing cumulative latency and operation counters.
///
/// Units:
///   latencyDelta   -- nonnegative cumulative latency nanosecond increment
///   operationsDelta -- nonnegative cumulative operation count increment
///   elapsedNs      -- one shared strictly positive wall-clock interval (nanoseconds)
///                     for CURRENT or CACHED; zero for BASELINE or UNAVAILABLE
///   observedAtNs   -- monotonic nanosecond timestamp of the observation
///
/// Pairing rule: a LatencyInterval is valid (CURRENT or CACHED) only when both the
///   latency and operations counters produced deltas over the same observation interval.
///   If either member is BASELINE or UNAVAILABLE, the pair is UNAVAILABLE and both
///   member counters are rebased in the engine.
///
/// Compact constructor enforces:
///   BASELINE/UNAVAILABLE => latencyDelta = 0, operationsDelta = 0, elapsedNs = 0
///   CURRENT/CACHED       => elapsedNs must be strictly positive (IllegalArgumentException)
///   Both deltas must be nonnegative for CURRENT/CACHED
public record LatencyInterval(long latencyDelta, long operationsDelta, long elapsedNs,
                              long observedAtNs, SignalResolution resolution) {
    public LatencyInterval {
        if (resolution == SignalResolution.BASELINE || resolution == SignalResolution.UNAVAILABLE) {
            latencyDelta = 0L;
            operationsDelta = 0L;
            elapsedNs = 0L;
        } else {
            if (elapsedNs <= 0) {
                throw new IllegalArgumentException(
                    "LatencyInterval elapsedNs must be > 0 for CURRENT/CACHED; got " + elapsedNs);
            }
            if (latencyDelta < 0) {
                throw new IllegalArgumentException(
                    "LatencyInterval latencyDelta must be >= 0; got " + latencyDelta);
            }
            if (operationsDelta < 0) {
                throw new IllegalArgumentException(
                    "LatencyInterval operationsDelta must be >= 0; got " + operationsDelta);
            }
        }
    }
}
