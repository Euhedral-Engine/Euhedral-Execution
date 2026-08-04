package io.euhedral_execution.hardware_utils.osx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OSXAffinityTest {

    @Test
    void appliesOneOrdinalAndUsesTagZeroForRelease() {
        AtomicReference<long[]> seen = new AtomicReference<>();
        assertFalse(OSXAffinityCalls.applyOrdinal(new long[]{3}, mask -> {
            seen.set(mask);
            return 0;
        }));
        assertTrue(OSXAffinityCalls.applyOrdinal(new long[]{1}, mask -> {
            seen.set(mask.clone());
            return 0;
        }));
        assertArrayEquals(new long[]{1}, seen.get());
        assertTrue(OSXAffinityCalls.raw(new long[]{0}, mask -> {
            seen.set(mask.clone());
            return 0;
        }));
        assertArrayEquals(new long[]{0}, seen.get());
        assertFalse(OSXAffinityCalls.raw(new long[]{0}, mask -> {
            throw new UnsatisfiedLinkError("configured failure");
        }));
    }
}
