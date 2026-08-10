package io.euhedral_execution.hardware_utils.macos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.AffinityCapability;
import io.euhedral_execution.hardware_utils.common.OSName;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MacosAffinityTest {

    @Test
    void validatesCapabilityAndGetCpu() {
        if (OSName.isMacOS()) {
            MacosAffinity provider = MacosAffinity.INSTANCE;
            if (provider != null) {
                assertEquals(AffinityCapability.LOCALITY_HINT, provider.capability());
                assertEquals(-1, provider.getCpu());
            }
        }
    }

    @Test
    void validatesSingleLocalityEnforcementAndMultiLocalityRejection() {
        AtomicReference<long[]> seen = new AtomicReference<>();

        // Multi-locality request with 2 set bits (0b11 = 3L) -> rejected
        assertFalse(MacosAffinityCalls.applyOrdinal(new long[] {3L}, mask -> {
            seen.set(mask);
            return 0;
        }));

        // Zero set bits (0L) -> rejected
        assertFalse(MacosAffinityCalls.applyOrdinal(new long[] {0L}, mask -> {
            seen.set(mask);
            return 0;
        }));

        // Single-locality request with 1 set bit (bit 0 = 1L -> ordinal 0 -> tag 1) -> accepted
        assertTrue(MacosAffinityCalls.applyOrdinal(new long[] {1L}, mask -> {
            seen.set(mask.clone());
            return 0;
        }));
        assertArrayEquals(new long[] {1L}, seen.get());

        // Single-locality request for ordinal 2 (1L << 2 = 4L -> ordinal 2 -> tag 3) -> accepted
        assertTrue(MacosAffinityCalls.applyOrdinal(new long[] {4L}, mask -> {
            seen.set(mask.clone());
            return 0;
        }));
        assertArrayEquals(new long[] {3L}, seen.get());
    }

    @Test
    void validatesTagZeroRelease() {
        AtomicReference<long[]> seen = new AtomicReference<>();
        assertTrue(MacosAffinityCalls.raw(new long[] {0L}, mask -> {
            seen.set(mask.clone());
            return 0;
        }));
        assertArrayEquals(new long[] {0L}, seen.get());

        assertFalse(MacosAffinityCalls.raw(new long[] {0L}, mask -> {
            throw new UnsatisfiedLinkError("configured link error");
        }));
    }

    @Test
    void validatesSafeTimerResolutionPolicy() {
        if (OSName.isMacOS() && MacosAffinity.INSTANCE != null) {
            assertTrue(MacosAffinity.INSTANCE.setTimerResolution(1_000_000L));
            assertTrue(MacosAffinity.INSTANCE.setTimerResolution(0L));
        }

        MacosAffinity provider = MacosAffinity.INSTANCE;
        if (provider != null) {
            assertThrows(IllegalArgumentException.class, () -> provider.setTimerResolution(-100L));
        }
    }
}
