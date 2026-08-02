package io.euhedral_execution.hardware_utils.internal;

import io.euhedral_execution.hardware_utils.AffinityCapability;

/// Operational boundary between common affinity policy and platform facades.
public interface AffinityProvider {

    /// Reports what this complete provider can honestly apply and later undo.
    AffinityCapability capability();

    /// Returns the calling thread's logical CPU, or `-1` when it cannot be determined exactly.
    default int currentCpu() {
        return -1;
    }

    /// Captures the calling thread's current exact CPU mask without changing it.
    ///
    /// @return a little-endian mask snapshot, or `null` when exact capture is unavailable
    default long[] captureAffinity() {
        return null;
    }

    /// Atomically applies every CPU bit in an exact mask.
    ///
    /// @param mask owned little-endian mask whose bit indexes are logical CPU IDs
    default boolean applyExact(long[] mask) {
        return false;
    }

    /// Restores a mask previously returned by `captureAffinity()`.
    ///
    /// @param mask owned snapshot of the thread's original exact binding
    default boolean restoreExact(long[] mask) {
        return false;
    }

    /// Maps a logical CPU to a scheduler-locality identifier.
    ///
    /// A locality is a placement preference, not a guarantee that the thread runs on that CPU.
    ///
    /// @return a positive locality identifier, or `-1` when no honest mapping exists
    default int localityForCpu(int cpu) {
        return -1;
    }

    /// Applies one scheduler locality preference after the whole request has been resolved.
    ///
    /// @param locality positive provider-specific locality identifier
    default boolean applyLocality(int locality) {
        return false;
    }

    /// Clears the calling thread's locality preference using the platform's neutral value.
    default boolean releaseLocality() {
        return false;
    }

    /// Requests timer granularity in nanoseconds independently of affinity placement.
    boolean setTimerResolution(long nanos);
}
