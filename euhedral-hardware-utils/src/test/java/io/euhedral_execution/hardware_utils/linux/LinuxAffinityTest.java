package io.euhedral_execution.hardware_utils.linux;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LinuxAffinityTest {

    @Test
    void validatesBeforeOneRawCall() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<long[]> seen = new AtomicReference<>();
        assertFalse(LinuxAffinityCalls.apply(new long[0], mask -> calls.incrementAndGet()));
        assertTrue(LinuxAffinityCalls.apply(new long[]{1}, mask -> {
            calls.incrementAndGet();
            seen.set(mask.clone());
            mask[0] = 0;
            return 0;
        }));
        assertArrayEquals(new long[]{1}, seen.get());
        assertFalse(LinuxAffinityCalls.apply(new long[]{1}, mask -> {
            calls.incrementAndGet();
            return 1;
        }));
        assertFalse(LinuxAffinityCalls.apply(new long[]{1}, mask -> {
            throw new IllegalStateException("configured failure");
        }));
        assertFalse(LinuxAffinityCalls.apply(new long[]{1}, mask -> {
            throw new UnsatisfiedLinkError("configured failure");
        }));
        assertEquals(2, calls.get());
    }
}
