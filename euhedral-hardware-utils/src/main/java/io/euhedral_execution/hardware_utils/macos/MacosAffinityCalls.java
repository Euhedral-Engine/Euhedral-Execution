package io.euhedral_execution.hardware_utils.macos;

import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.internal.AffinityMasks;
import java.util.BitSet;

/// Single-locality mask canonicalization and ordinal-to-tag mapping helper for macOS affinity hints.
public final class MacosAffinityCalls {

    /// Converts one requested logical CPU ordinal to one nonzero macOS locality tag.
    ///
    /// macOS locality is scheduler preference rather than exact binding. Multiple distinct ordinals
    /// or empty masks are rejected because single-locality enforcement requires cardinality 1.
    public static boolean applyOrdinal(long[] masks, RawCall call) {
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
    public static boolean raw(long[] mask, RawCall call) {
        try {
            return call.apply(mask.clone()) == 0;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private MacosAffinityCalls() {
    }

    /// JNI-shaped macOS affinity-tag setter returning `0` on success.
    @FunctionalInterface
    public interface RawCall {

        int apply(long[] masks);
    }
}
