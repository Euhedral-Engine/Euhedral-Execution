package io.euhedral_execution.hardware_utils.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WindowsAffinityTest {

    @Test
    void rejectsCrossGroupBeforeRawCall() {
        AtomicInteger calls = new AtomicInteger();
        assertFalse(WindowsAffinityCalls.apply(new long[]{1, 1}, mask -> {
            calls.incrementAndGet();
            return 0;
        }));
        assertEquals(0, calls.get());

        assertTrue(WindowsAffinityCalls.apply(new long[]{1}, mask -> {
            calls.incrementAndGet();
            return 0;
        }));
        assertEquals(1, calls.get());
    }
}
