package io.euhedral_execution.hardware_utils.internal.sampling;

import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.SlowHardwareSample;

/// Internal SPI for reading detailed hardware samples.
///
/// Implementations must not return null from sampleFast or sampleSlow.
/// A null return from the provider violates the SPI contract, though the monitor
/// adapter will convert a null snapshot into a transient failure.
/// Implementations may throw any Exception or LinkageError to signal a hard
/// retrieval failure.
public interface DetailedSystemSnapshotProvider extends SystemSnapshotProvider {

    /// Reads a fast-cadence sample (CPU utilization, wait/stall, memory, I/O).
    ///
    /// Must not return null. The requestedAtNs timestamp should be used as the
    /// observedAtNs for the returned sample and its signals, unless the provider
    /// reads its own high-resolution timestamp during retrieval.
    FastHardwareSample sampleFast(long requestedAtNs);

    /// Reads a slow-cadence sample (capacity, frequency, thermal, low-power).
    ///
    /// Must not return null. The requestedAtNs timestamp should be used as the
    /// observedAtNs for the returned sample and its signals, unless the provider
    /// reads its own high-resolution timestamp during retrieval.
    SlowHardwareSample sampleSlow(long requestedAtNs);
}
