package io.euhedral_execution.hardware_utils.internal.sampling;

import io.euhedral_execution.hardware_utils.internal.sampling.samples.SlowHardwareSample;

/// Anchors an independent 5-second slow-sample attempt grid and retains the last
/// successful slow sample within a 15-second TTL.
///
/// Ownership: monitor-instance-owned; called only from the monitor thread.
/// Thread-safety: not thread-safe; all access must be confined to one thread.
///
/// Grid rule: the first call to anchorAndStore anchors the grid at
///   nextAttemptNs = pollStartNs + SLOW_PERIOD_NS. Subsequent calls advance
///   nextAttemptNs by the minimum number of full periods that places it strictly
///   after pollStartNs (first-future rule). A slow failure does not anchor
///   the grid; call anchorAndStore only on success.
///
/// Stop/close semantics: retainForStop() preserves the sample and grid for
///   restart. clear() wipes both, used by close() and clock-regression reset.
public final class SlowSampleCache {

    /// Fixed slow-polling period: 5 seconds in nanoseconds.
    private static final long SLOW_PERIOD_NS = 5_000_000_000L;

    /// Maximum age at which a slow sample remains usable: 15 seconds in nanoseconds.
    private static final long SLOW_TTL_NS = 15_000_000_000L;

    /// Monotonic nanosecond timestamp of the next scheduled slow attempt.
    /// Zero means the grid has not been anchored yet.
    private long nextAttemptNs;

    /// The last successfully stored slow sample, or null if none.
    private SlowHardwareSample lastSample;

    public SlowSampleCache() {
        this.nextAttemptNs = 0L;
    }

    /// Returns true if a slow sample attempt is due at the given poll-start time.
    /// The grid is considered due on the first call (nextAttemptNs == 0) or when
    /// pollStartNs >= nextAttemptNs under signed elapsed ordering.
    public boolean isDue(long pollStartNs) {
        if (nextAttemptNs == 0L) {
            return true;
        }
        return (pollStartNs - nextAttemptNs) >= 0;
    }

    /// Stores a successful slow sample and advances the grid anchor.
    ///
    /// On the first call (nextAttemptNs == 0), the grid is anchored so that the
    /// next attempt is scheduled SLOW_PERIOD_NS after pollStartNs.
    ///
    /// On subsequent calls the grid advances by the minimum number of full
    /// SLOW_PERIOD_NS periods that places nextAttemptNs strictly after
    /// pollStartNs (first-future rule). This skips over missed boundaries
    /// without attempting catch-up.
    ///
    /// sample must not be null; call this only on a successful provider result.
    public void anchorAndStore(long pollStartNs, SlowHardwareSample sample) {
        if (nextAttemptNs == 0L) {
            // First anchor: schedule next attempt one period from now.
            nextAttemptNs = pollStartNs + SLOW_PERIOD_NS;
        } else {
            long diff = pollStartNs - nextAttemptNs;
            if (diff >= 0) {
                // Advance by minimum periods to place nextAttemptNs past pollStartNs.
                long periods = (diff / SLOW_PERIOD_NS) + 1;
                nextAttemptNs += periods * SLOW_PERIOD_NS;
            }
        }
        this.lastSample = sample;
    }

    /// Returns the cached slow sample if its age from evaluationNs is within
    /// SLOW_TTL_NS, otherwise clears and returns null.
    ///
    /// Age is computed as evaluationNs - sample.observedAtNs() using signed
    /// subtraction. A negative age (clock anomaly) treats the sample as expired.
    public SlowHardwareSample resolve(long evaluationNs) {
        if (lastSample == null) {
            return null;
        }
        long age = evaluationNs - lastSample.observedAtNs();
        if (age < 0 || age > SLOW_TTL_NS) {
            lastSample = null;
            return null;
        }
        return lastSample;
    }

    /// Clears the cached sample and resets the grid anchor.
    /// Used by close() and clock-regression resets. Does not preserve the sample
    /// for restart; use this only when the engine is being fully torn down or
    /// when a clock regression requires discarding all state.
    public void clear() {
        this.lastSample = null;
        this.nextAttemptNs = 0L;
    }

    /// Retains the current sample and grid anchor across a stop/restart cycle.
    /// After this call the sample remains available to resolve() and isDue()
    /// continues from the existing schedule. This satisfies the specification
    /// requirement that stop retains fresh slow state for restart.
    ///
    /// This is a no-op at the data level; it documents the intended caller contract.
    /// P4-D must call this (rather than clear()) when stopping the monitor.
    public void retainForStop() {
        // Intentionally empty: the sample and grid are retained by not calling clear().
    }
}
