package io.euhedral_execution.hardware_utils.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WindowsAffinityTest {

    @Test
    void passesValidMaskToRawCall() {
        AtomicInteger calls = new AtomicInteger();
        assertTrue(WindowsAffinityCalls.apply(new long[]{1}, mask -> {
            calls.incrementAndGet();
            return 0;
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void returnsFalseWhenRawCallFails() {
        AtomicInteger calls = new AtomicInteger();
        assertFalse(WindowsAffinityCalls.apply(new long[]{1}, mask -> {
            calls.incrementAndGet();
            return -1; // Native error code / failure
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsEmptyOrInvalidMasks() {
        AtomicInteger calls = new AtomicInteger();
        assertFalse(WindowsAffinityCalls.apply(new long[]{0}, mask -> {
            calls.incrementAndGet();
            return 0;
        }));
        assertFalse(WindowsAffinityCalls.apply(new long[]{}, mask -> {
            calls.incrementAndGet();
            return 0;
        }));
        assertFalse(WindowsAffinityCalls.apply(null, mask -> {
            calls.incrementAndGet();
            return 0;
        }));
        assertEquals(0, calls.get());
    }

    @Test
    void timerResolutionRejectsNegativeValue() {
        if (WindowsAffinity.INSTANCE != null) {
            assertThrows(IllegalArgumentException.class, () -> {
                WindowsAffinity.INSTANCE.setTimerResolution(-1000L);
            });
        }
    }
}
