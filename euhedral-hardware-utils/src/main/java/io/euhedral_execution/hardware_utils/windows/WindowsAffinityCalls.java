package io.euhedral_execution.hardware_utils.windows;

import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.internal.AffinityMasks;
import java.util.BitSet;

final class WindowsAffinityCalls {

    /// Applies a validated mask across available Windows group words.
    ///
    /// @param masks little-endian words, one word per possible 64-CPU processor group
    /// @param call  injectable JNI-shaped operation for production and deterministic tests
    static boolean apply(long[] masks, RawCall call) {
        BitSet supported = SystemInfo.getCpuSet();
        long[] request = AffinityMasks.canonical(masks, SystemInfo.getCpuCount(), supported);
        if (request == null || AffinityMasks.nonzeroWords(request) == 0) {
            return false;
        }
        try {
            return call.apply(request.clone()) == 0;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private WindowsAffinityCalls() {
    }

    /// JNI-shaped Windows affinity setter returning `0` on success.
    @FunctionalInterface
    interface RawCall {

        int apply(long[] masks);
    }
}
