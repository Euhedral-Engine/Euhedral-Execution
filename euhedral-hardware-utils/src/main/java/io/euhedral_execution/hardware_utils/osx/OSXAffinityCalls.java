package io.euhedral_execution.hardware_utils.osx;

import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.internal.AffinityMasks;
import java.util.BitSet;

final class OSXAffinityCalls {

    /// Converts one requested logical CPU ordinal to one nonzero macOS locality tag.
    ///
    /// macOS locality is scheduler preference rather than exact binding. Multiple distinct ordinals
    /// are rejected because P3-A cannot prove that they share one locality.
    static boolean applyOrdinal(long[] masks, RawCall call) {
        BitSet supported = SystemInfo.getCpuSet();
        long[] request = AffinityMasks.canonical(masks, SystemInfo.getCpuCount(), supported);
        if (request == null) {
            return false;
        }
        BitSet bits = BitSet.valueOf(request);
        if (bits.cardinality() != 1) {
            return false;
        }
        return raw(new long[]{bits.nextSetBit(0) + 1L}, call);
    }

    /// Sends one encoded locality tag; tag `0` clears the preference.
    ///
    /// @param mask single-word provider encoding, not a logical CPU request mask
    /// @param call injectable JNI-shaped operation for production and deterministic tests
    static boolean raw(long[] mask, RawCall call) {
        try {
            return call.apply(mask.clone()) == 0;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private OSXAffinityCalls() {
    }

    /// JNI-shaped macOS affinity-tag setter returning `0` on success.
    @FunctionalInterface
    interface RawCall {

        int apply(long[] masks);
    }
}
