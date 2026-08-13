package io.euhedral_execution.core.flow_control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AcquisitionContentionSmootherTest {

    @Test
    void firstValidSampleBootstrapsExactly() {
        AcquisitionContentionSmoother smoother = new AcquisitionContentionSmoother();

        smoother.record(375_000L);

        assertTrue(smoother.initialized());
        assertEquals(375_000L, smoother.value());
    }

    @Test
    void repeatedEqualSamplesRemainStable() {
        AcquisitionContentionSmoother smoother = new AcquisitionContentionSmoother();
        smoother.record(625_000L);

        for (int sample = 0; sample < 1_000; sample++) {
            smoother.record(625_000L);
        }

        assertEquals(625_000L, smoother.value());
    }

    @Test
    void lowHighLowStepsMoveMonotonicallyAndStayBounded() {
        AcquisitionContentionSmoother smoother = new AcquisitionContentionSmoother();
        smoother.record(0L);

        long previous = smoother.value();
        for (int sample = 0; sample < 256; sample++) {
            smoother.record(UpstreamQueue.ACQUIRE_CONTENTION_SCALE);
            assertTrue(smoother.value() >= previous);
            assertTrue(smoother.value() <= UpstreamQueue.ACQUIRE_CONTENTION_SCALE);
            previous = smoother.value();
        }
        assertTrue(smoother.value() >= UpstreamQueue.ACQUIRE_CONTENTION_SCALE - AcquisitionContentionSmoother.DIVISOR);

        for (int sample = 0; sample < 256; sample++) {
            smoother.record(0L);
            assertTrue(smoother.value() <= previous);
            assertTrue(smoother.value() >= 0L);
            previous = smoother.value();
        }
        assertTrue(smoother.value() < AcquisitionContentionSmoother.DIVISOR);
    }

    @Test
    void resetReturnsToUninitializedState() {
        AcquisitionContentionSmoother smoother = new AcquisitionContentionSmoother();
        smoother.record(500_000L);

        smoother.reset();

        assertFalse(smoother.initialized());
        assertEquals(0L, smoother.value());
    }

    @Test
    void samplesMustRemainWithinTheFixedPointRange() {
        AcquisitionContentionSmoother smoother = new AcquisitionContentionSmoother();

        assertThrows(IllegalArgumentException.class, () -> smoother.record(-1L));
        assertThrows(
                IllegalArgumentException.class, () -> smoother.record(UpstreamQueue.ACQUIRE_CONTENTION_SCALE + 1L));
        assertFalse(smoother.initialized());
    }
}
