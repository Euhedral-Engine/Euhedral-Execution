package io.euhedral_execution.hardware_utils.linux;

import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.internal.AffinityMasks;
import java.util.BitSet;

final class LinuxAffinityCalls {

    private LinuxAffinityCalls() {}

    /// Validates the complete logical CPU mask before invoking one Linux raw setter.
    ///
    /// @param masks little-endian words whose bit indexes are logical CPU IDs
    /// @param call  injectable JNI-shaped operation for production and deterministic tests
    static boolean apply(long[] masks, RawCall call) {
        BitSet supported = SystemInfo.getCpuSet();
        long[] request = AffinityMasks.canonical(masks, SystemInfo.getCpuCount(), supported);
        if (request == null) {
            return false;
        }
        try {
            return call.apply(request.clone()) == 0;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /// JNI-shaped Linux affinity setter returning `0` on success.
    @FunctionalInterface
    interface RawCall {

        int apply(long[] masks);
    }
}
