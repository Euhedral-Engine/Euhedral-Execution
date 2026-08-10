package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FragmentControlPolicyTest {

    /// Verifies the deterministic state used at construction and trial reset.
    @Test
    void startsAndResetsInDirectModeAtBatchTwo() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        policy.recordExecution(8_000L, 2L);
        policy.completeBatch(4_096L);
        policy.missRequiresPark();

        policy.reset();

        assertEquals(FragmentControlPolicy.Mode.DIRECT, policy.mode());
        assertEquals(2L, policy.batchSize());
        assertEquals(0.0, policy.serviceTimeNs());
        assertEquals(0, policy.transitionStreak());
        assertFalse(policy.missRequiresPark());
    }

    /// Verifies first-sample initialization and the exact one-eighth EWMA update.
    @Test
    void updatesServiceTimeWithOneEighthEwma() {
        FragmentControlPolicy policy = new FragmentControlPolicy();

        policy.recordExecution(8_000L, 2L);
        assertEquals(4_000.0, policy.serviceTimeNs());

        policy.recordExecution(4_000L, 2L);
        assertEquals(3_750.0, policy.serviceTimeNs());

        policy.recordExecution(0L, 2L);
        policy.recordExecution(10L, 0L);
        assertEquals(3_750.0, policy.serviceTimeNs());
    }

    /// Verifies target rounding, hard caps, and the two-times growth rate limit.
    @Test
    void roundsTargetDownAndMovesBatchAtMostTwoTimes() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        policy.recordExecution(70L, 1L);

        assertEquals(4L, policy.completeBatch(4_096L));
        assertEquals(8L, policy.completeBatch(4_096L));
        assertEquals(16L, policy.completeBatch(4_096L));
        assertEquals(32L, policy.completeBatch(4_096L));
        assertEquals(64L, policy.completeBatch(4_096L));
        assertEquals(128L, policy.completeBatch(4_096L));
        assertEquals(256L, policy.completeBatch(4_096L));
        assertEquals(512L, policy.completeBatch(4_096L));
        assertEquals(1_024L, policy.completeBatch(4_096L));
        assertEquals(2_048L, policy.completeBatch(4_096L));
        assertEquals(2_048L, policy.completeBatch(4_096L));

        FragmentControlPolicy capped = new FragmentControlPolicy();
        capped.recordExecution(1L, 1L);
        for (int expected = 4; expected <= 2_048; expected <<= 1) {
            assertEquals(expected, capped.completeBatch(3_000L));
        }
        assertEquals(3_000L, capped.completeBatch(3_000L));
    }

    /// Verifies high service time must persist for exactly eight completed batches.
    @Test
    void entersStagedModeAtTheInclusiveHighThresholdAfterEightBatches() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        policy.recordExecution((long) FragmentControlPolicy.STAGED_THRESHOLD_NS, 1L);

        for (int i = 0; i < 7; i++) {
            policy.completeBatch(4_096L);
            assertEquals(FragmentControlPolicy.Mode.DIRECT, policy.mode());
        }

        policy.completeBatch(4_096L);
        assertEquals(FragmentControlPolicy.Mode.STAGED, policy.mode());
        assertEquals(64L, policy.batchSize());
        assertEquals(0, policy.transitionStreak());
    }

    /// Verifies staged execution amortizes routing with its longer aggregate-work target.
    @Test
    void growsTowardTheStagedWorkTargetAfterTransition() {
        FragmentControlPolicy policy = enterStagedMode();

        assertEquals(64L, policy.batchSize());
        assertEquals(128L, policy.completeBatch(4_096L));
        assertEquals(256L, policy.completeBatch(4_096L));
        assertEquals(512L, policy.completeBatch(4_096L));
        assertEquals(1_024L, policy.completeBatch(4_096L));
        assertEquals(1_024L, policy.completeBatch(4_096L));
    }

    /// Verifies an out-of-region batch clears partially accumulated hysteresis.
    @Test
    void resetsTransitionStreakOutsideTheActiveThreshold() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        policy.recordExecution((long) FragmentControlPolicy.STAGED_THRESHOLD_NS, 1L);
        for (int i = 0; i < 4; i++) {
            policy.completeBatch(4_096L);
        }
        assertEquals(4, policy.transitionStreak());

        policy.recordExecution(1L, 1L);
        policy.completeBatch(4_096L);

        assertEquals(FragmentControlPolicy.Mode.DIRECT, policy.mode());
        assertEquals(0, policy.transitionStreak());
    }

    /// Verifies staged mode returns at the inclusive low threshold after eight boundaries.
    @Test
    void returnsToDirectModeAfterEightLowServiceBatches() {
        FragmentControlPolicy policy = enterStagedMode();
        while (policy.serviceTimeNs() > FragmentControlPolicy.DIRECT_THRESHOLD_NS) {
            policy.recordExecution(1L, 1L);
        }

        for (int i = 0; i < 7; i++) {
            policy.completeBatch(4_096L);
            assertEquals(FragmentControlPolicy.Mode.STAGED, policy.mode());
        }

        policy.completeBatch(4_096L);
        assertEquals(FragmentControlPolicy.Mode.DIRECT, policy.mode());
    }

    /// Verifies active misses spin 64 times, then park until productive work resets the streak.
    @Test
    void boundsActiveMissSpinning() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        for (int i = 0; i < FragmentControlPolicy.SPIN_MISSES; i++) {
            assertFalse(policy.missRequiresPark());
        }
        assertTrue(policy.missRequiresPark());
        assertTrue(policy.missRequiresPark());

        policy.recordProgress();
        assertFalse(policy.missRequiresPark());
    }

    /// Verifies the batch growth guard saturates instead of wrapping negative.
    @Test
    void doublesWithoutOverflow() {
        assertEquals(8L, FragmentControlPolicy.saturatingDouble(4L));
        assertEquals(Long.MAX_VALUE, FragmentControlPolicy.saturatingDouble(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, FragmentControlPolicy.saturatingDouble(Long.MAX_VALUE / 2L + 1L));
    }

    /// Creates a policy that has completed the direct-to-staged hysteresis.
    private static FragmentControlPolicy enterStagedMode() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        policy.recordExecution((long) FragmentControlPolicy.STAGED_THRESHOLD_NS, 1L);
        for (int i = 0; i < FragmentControlPolicy.TRANSITION_BATCHES; i++) {
            policy.completeBatch(4_096L);
        }
        assertEquals(FragmentControlPolicy.Mode.STAGED, policy.mode());
        return policy;
    }
}
