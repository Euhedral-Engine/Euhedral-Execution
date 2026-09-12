package io.euhedral_execution.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StopWatchTest {

    @Test
    void stopWithoutStartReturnsZero() {
        StopWatch stopWatch = new StopWatch(1);
        assertEquals(0L, stopWatch.stop());
    }

    @Test
    void singleIntervalMeasurement() {
        StopWatch stopWatch = new StopWatch(1);
        stopWatch.start();
        long elapsed = stopWatch.stop();
        assertTrue(elapsed >= 0L);
    }

    @Test
    void intervalSampling() {
        StopWatch stopWatch = new StopWatch(4);

        // tick 0: triggers start
        stopWatch.start();
        long elapsed0 = stopWatch.stop();
        assertTrue(elapsed0 >= 0L);

        // tick 1: not ready, returns 0 on stop
        stopWatch.start();
        assertEquals(0L, stopWatch.stop());

        // tick 2: not ready, returns 0 on stop
        stopWatch.start();
        assertEquals(0L, stopWatch.stop());

        // tick 3: not ready, returns 0 on stop
        stopWatch.start();
        assertEquals(0L, stopWatch.stop());

        // tick 4: triggers start
        stopWatch.start();
        long elapsed4 = stopWatch.stop();
        assertTrue(elapsed4 >= 0L);
    }

    @Test
    void invalidIntervalThrows() {
        assertThrows(IllegalArgumentException.class, () -> new StopWatch(-1));
    }
}
