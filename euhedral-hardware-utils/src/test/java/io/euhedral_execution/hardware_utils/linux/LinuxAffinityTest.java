package io.euhedral_execution.hardware_utils.linux;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LinuxAffinityTest {

    @Test
    void validatesBeforeOneRawCall() {
        AtomicInteger calls = new AtomicInteger();
        assertFalse(LinuxAffinityCalls.apply(new long[0], mask -> calls.incrementAndGet()));
        assertTrue(LinuxAffinityCalls.apply(new long[]{1}, mask -> {
            calls.incrementAndGet();
            return 0;
        }));
        assertFalse(LinuxAffinityCalls.apply(new long[]{1}, mask -> {
            calls.incrementAndGet();
            return 1;
        }));
        assertTrue(calls.get() >= 2);
    }
}
