package io.euhedral_execution.hardware_utils.internal;

import io.euhedral_execution.hardware_utils.AffinityCapability;
import io.euhedral_execution.hardware_utils.linux.LinuxAffinity;
import io.euhedral_execution.hardware_utils.osx.OSXAffinity;
import io.euhedral_execution.hardware_utils.windows.WindowsAffinity;

public abstract sealed class ThreadPinner implements AffinityProvider permits LinuxAffinity,
        OSXAffinity, WindowsAffinity {

    private final AffinityCapability capability;
    private final RawLocalityCall localityCall;

    /// Creates a platform facade whose common affinity path is unsupported.
    protected ThreadPinner() {
        this(io.euhedral_execution.hardware_utils.AffinityCapability.UNSUPPORTED, null);
    }

    /// Creates a facade with an operational capability and optional locality raw call.
    ///
    /// @param capability   complete common-path behavior supplied by this facade
    /// @param localityCall raw setter for one locality tag and the neutral tag `0`
    protected ThreadPinner(io.euhedral_execution.hardware_utils.AffinityCapability capability,
            RawLocalityCall localityCall) {
        this.capability = capability;
        this.localityCall = localityCall;
    }

    public abstract int getCpu();

    public abstract boolean setAffinity(long[] masks);

    public abstract boolean setTimerResolution(long nanos);

    @Override
    public final AffinityCapability capability() {
        return capability;
    }

    @Override
    public final int currentCpu() {
        return capability == AffinityCapability.EXACT ? getCpu() : -1;
    }

    /// Maps a process-visible logical CPU ordinal to its nonzero macOS locality tag.
    @Override
    public final int localityForCpu(int cpu) {
        return capability == AffinityCapability.LOCALITY_HINT ? cpu + 1 : -1;
    }

    /// Applies one already-resolved locality tag through the raw platform setter.
    @Override
    public final boolean applyLocality(int locality) {
        return localityCall != null && localityCall.apply(new long[]{locality}) == 0;
    }

    /// Clears a locality preference by sending the neutral tag `0`.
    @Override
    public final boolean releaseLocality() {
        return localityCall != null && localityCall.apply(new long[]{0}) == 0;
    }

    /// Raw platform call accepting the provider's encoded locality mask.
    @FunctionalInterface
    protected interface RawLocalityCall {

        int apply(long[] mask);
    }
}
