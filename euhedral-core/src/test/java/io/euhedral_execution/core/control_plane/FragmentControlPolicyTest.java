package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.BitSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
class FragmentControlPolicyTest {

    private FragmentControlPolicy.DiagnosticOverride diagnosticOverride;

    /// Clears setup-only policy state even when a diagnostic fixture assertion fails.
    @AfterEach
    void clearDiagnosticOverride() {
        if (this.diagnosticOverride != null) {
            FragmentControlPolicy.clearDiagnosticOverride(this.diagnosticOverride);
            this.diagnosticOverride = null;
        }
    }

    /// Verifies the deterministic state used at construction and trial reset.
    @Test
    void startsAndResetsInDirectModeAtBatchTwo() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        recordBodySamples(policy, 100L, FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES);
        policy.recordExecution(8_000L, 2L);
        policy.completeBatch(4_096L, 1L, 2);
        policy.missRequiresPark();

        policy.reset();

        assertEquals(FragmentControlPolicy.Mode.DIRECT, policy.mode());
        assertEquals(2L, policy.batchSize());
        assertEquals(0.0, policy.serviceTimeNs());
        assertEquals(0, policy.bodyCostHistoryCount());
        assertEquals(0.0, policy.smoothedBodyCostNs());
        assertFalse(policy.missRequiresPark());
    }

    /// Verifies first-sample initialization and the exact one-eighth service EWMA update.
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

    /// Verifies non-overlapping second minima and two-window expensive confirmation.
    @Test
    void recordsBodyCostWindowAndConfirmsExpensiveWork() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        for (int sample = 1; sample < FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES; sample++) {
            policy.recordBodyCost(sample * 10L);
            assertEquals(sample, policy.bodyCostHistoryCount());
            assertEquals(0.0, policy.smoothedBodyCostNs());
        }

        policy.recordBodyCost(320L);

        assertEquals(32, policy.bodyCostHistoryCount());
        assertEquals(20.0, policy.smoothedBodyCostNs());

        recordBodySamples(policy, 330L, FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES);

        assertEquals(64, policy.bodyCostHistoryCount());
        assertEquals(20.0, policy.smoothedBodyCostNs());

        recordBodySamples(policy, 92L, FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES);

        assertEquals(96, policy.bodyCostHistoryCount());
        assertEquals(92.0, policy.smoothedBodyCostNs());

        recordBodySamples(policy, 330L, FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES);

        assertEquals(128, policy.bodyCostHistoryCount());
        assertEquals(92.0, policy.smoothedBodyCostNs());

        recordBodySamples(policy, 330L, FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES);

        assertEquals(160, policy.bodyCostHistoryCount());
        assertEquals(330.0, policy.smoothedBodyCostNs());

        recordBodySamples(policy, 30L, FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES);

        assertEquals(192, policy.bodyCostHistoryCount());
        assertEquals(30.0, policy.smoothedBodyCostNs());
    }

    /// Verifies invalid body samples cannot advance initialization or alter an established estimate.
    @Test
    void ignoresNonPositiveBodySamples() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        policy.recordBodyCost(0L);
        policy.recordBodyCost(-1L);

        assertEquals(0, policy.bodyCostHistoryCount());
        assertEquals(0.0, policy.smoothedBodyCostNs());
    }

    /// Verifies the history counter saturates instead of wrapping back into startup behavior.
    @Test
    void saturatesBodyHistoryCount() throws Exception {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        recordBodySamples(policy, 100L, FragmentControlPolicy.EXPENSIVE_CONFIRMATION_SAMPLES);
        Field count = FragmentControlPolicy.class.getDeclaredField("bodyCostHistoryCount");
        count.setAccessible(true);
        count.setInt(policy, Integer.MAX_VALUE);

        policy.recordBodyCost(100L);

        assertEquals(Integer.MAX_VALUE, policy.bodyCostHistoryCount());
        assertEquals(100.0, policy.smoothedBodyCostNs());
    }

    /// Verifies the complete explicit tree, including exact guard boundaries and retained mode.
    @Test
    void selectsModeFromAvailabilityHistoryAndGuardBand() {
        FragmentControlPolicy.Mode direct = FragmentControlPolicy.Mode.DIRECT;
        FragmentControlPolicy.Mode staged = FragmentControlPolicy.Mode.STAGED;

        int history = FragmentControlPolicy.BODY_COST_MIN_HISTORY;
        assertEquals(direct, FragmentControlPolicy.selectMode(0L, 0, history, 100.0, staged));
        assertEquals(direct, FragmentControlPolicy.selectMode(2L, 2, history, 100.0, staged));
        assertEquals(direct, FragmentControlPolicy.selectMode(1L, 2, history - 1, 100.0, staged));
        assertEquals(direct, FragmentControlPolicy.selectMode(1L, 2, history, 90.0, staged));
        assertEquals(staged, FragmentControlPolicy.selectMode(1L, 2, history, 95.0, direct));
        assertEquals(direct, FragmentControlPolicy.selectMode(1L, 2, history, 92.5, direct));
        assertEquals(staged, FragmentControlPolicy.selectMode(1L, 2, history, 92.5, staged));
    }

    /// Verifies service telemetry still sizes batches but never selects the execution path.
    @Test
    void serviceTimeControlsBatchSizeButNotMode() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        policy.recordExecution(10_000L, 1L);

        for (int i = 0; i < 16; i++) {
            policy.completeBatch(4_096L, 1L, 2);
        }

        assertEquals(FragmentControlPolicy.Mode.DIRECT, policy.mode());
        assertEquals(16L, policy.batchSize());
    }

    /// Verifies sufficient availability immediately settles DIRECT while retaining body history.
    @Test
    void availabilityChangesReevaluateRetainedBodyCostAtBoundaries() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        recordBodySamples(policy, 100L, FragmentControlPolicy.EXPENSIVE_CONFIRMATION_SAMPLES);

        policy.completeBatch(4_096L, 1L, 2);
        assertEquals(FragmentControlPolicy.Mode.STAGED, policy.mode());

        policy.completeBatch(4_096L, 2L, 2);
        assertEquals(FragmentControlPolicy.Mode.DIRECT, policy.mode());
        assertEquals(FragmentControlPolicy.EXPENSIVE_CONFIRMATION_SAMPLES, policy.bodyCostHistoryCount());

        policy.completeBatch(4_096L, 1L, 2);
        assertEquals(FragmentControlPolicy.Mode.STAGED, policy.mode());
    }

    /// Verifies estimator updates alone cannot switch the mode inside an active batch.
    @Test
    void bodySamplesChangeModeOnlyAtCompletedBatchBoundary() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        recordBodySamples(policy, 100L, FragmentControlPolicy.EXPENSIVE_CONFIRMATION_SAMPLES);

        assertEquals(FragmentControlPolicy.Mode.DIRECT, policy.mode());

        policy.completeBatch(4_096L, 1L, 2);

        assertEquals(FragmentControlPolicy.Mode.STAGED, policy.mode());
    }

    /// Verifies repeated guard-band samples retain either already-settled execution path.
    @Test
    void guardBandDoesNotOscillateSettledMode() {
        FragmentControlPolicy direct = new FragmentControlPolicy();
        recordBodySamples(direct, 92L, FragmentControlPolicy.BODY_COST_MIN_HISTORY);
        for (int i = 0; i < 16; i++) {
            direct.completeBatch(4_096L, 1L, 2);
            assertEquals(FragmentControlPolicy.Mode.DIRECT, direct.mode());
        }

        FragmentControlPolicy staged = new FragmentControlPolicy();
        recordBodySamples(staged, 100L, FragmentControlPolicy.EXPENSIVE_CONFIRMATION_SAMPLES);
        staged.completeBatch(4_096L, 1L, 2);
        recordBodySamples(staged, 92L, FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES);
        assertTrue(staged.smoothedBodyCostNs() > FragmentControlPolicy.CHEAP_BODY_COST_MAX_NS);
        assertTrue(staged.smoothedBodyCostNs() < FragmentControlPolicy.EXPENSIVE_BODY_COST_MIN_NS);
        for (int i = 0; i < 16; i++) {
            staged.completeBatch(4_096L, 1L, 2);
            assertEquals(FragmentControlPolicy.Mode.STAGED, staged.mode());
        }
    }

    /// Verifies target rounding, hard caps, and the two-times growth rate limit remain intact.
    @Test
    void roundsTargetDownAndMovesBatchAtMostTwoTimes() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        policy.recordExecution(70L, 1L);

        assertEquals(4L, policy.completeBatch(4_096L, 2L, 2));
        assertEquals(8L, policy.completeBatch(4_096L, 2L, 2));
        assertEquals(16L, policy.completeBatch(4_096L, 2L, 2));
        assertEquals(32L, policy.completeBatch(4_096L, 2L, 2));
        assertEquals(64L, policy.completeBatch(4_096L, 2L, 2));
        assertEquals(128L, policy.completeBatch(4_096L, 2L, 2));
        assertEquals(256L, policy.completeBatch(4_096L, 2L, 2));
        assertEquals(512L, policy.completeBatch(4_096L, 2L, 2));
        assertEquals(1_024L, policy.completeBatch(4_096L, 2L, 2));
        assertEquals(2_048L, policy.completeBatch(4_096L, 2L, 2));
        assertEquals(2_048L, policy.completeBatch(4_096L, 2L, 2));

        FragmentControlPolicy capped = new FragmentControlPolicy();
        capped.recordExecution(1L, 1L);
        for (int expected = 4; expected <= 2_048; expected <<= 1) {
            assertEquals(expected, capped.completeBatch(3_000L, 2L, 2));
        }
        assertEquals(3_000L, capped.completeBatch(3_000L, 2L, 2));
    }

    /// Verifies staged execution retains its longer aggregate-work batch target.
    @Test
    void growsTowardStagedWorkTarget() {
        FragmentControlPolicy policy = new FragmentControlPolicy();
        recordBodySamples(policy, 100L, FragmentControlPolicy.EXPENSIVE_CONFIRMATION_SAMPLES);
        policy.recordExecution(8_000L, 1L);

        assertEquals(4L, policy.completeBatch(4_096L, 1L, 2));
        assertEquals(FragmentControlPolicy.Mode.STAGED, policy.mode());
        assertEquals(8L, policy.completeBatch(4_096L, 1L, 2));
        assertEquals(16L, policy.completeBatch(4_096L, 1L, 2));
        assertEquals(32L, policy.completeBatch(4_096L, 1L, 2));
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

    /// Verifies an override affects only policies constructed after its release publication.
    @Test
    void diagnosticOverrideIsCapturedOnlyAtConstruction() {
        FragmentControlPolicy existing = new FragmentControlPolicy();
        this.diagnosticOverride =
                FragmentControlPolicy.installDiagnosticOverride(FragmentControlPolicy.Mode.STAGED, 32L);

        FragmentControlPolicy captured = new FragmentControlPolicy();

        assertEquals(FragmentControlPolicy.Mode.DIRECT, existing.mode());
        assertEquals(FragmentControlPolicy.Mode.STAGED, captured.mode());
        assertFalse(captured.bodyCostSamplingEnabled());
    }

    /// Verifies forced modes bypass normal selection with sampling disabled or explicitly enabled.
    @Test
    void diagnosticModesRemainFixedAndSamplingIsExplicit() {
        this.diagnosticOverride =
                FragmentControlPolicy.installDiagnosticOverride(FragmentControlPolicy.Mode.DIRECT, 32L);
        FragmentControlPolicy direct = new FragmentControlPolicy();
        recordBodySamples(direct, 100L, FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES);
        direct.completeBatch(4_096L, 1L, 2);
        assertEquals(FragmentControlPolicy.Mode.DIRECT, direct.mode());
        assertFalse(direct.bodyCostSamplingEnabled());

        FragmentControlPolicy.clearDiagnosticOverride(this.diagnosticOverride);
        this.diagnosticOverride =
                FragmentControlPolicy.installDiagnosticOverride(FragmentControlPolicy.Mode.STAGED, 32L, true);
        FragmentControlPolicy staged = new FragmentControlPolicy();
        recordBodySamples(staged, 10L, FragmentControlPolicy.BODY_COST_WINDOW_SAMPLES);
        staged.completeBatch(4_096L, 2L, 2);
        assertEquals(FragmentControlPolicy.Mode.STAGED, staged.mode());
        assertTrue(staged.bodyCostSamplingEnabled());
    }

    /// Verifies fixed diagnostic polling leaves normal selection and sampling intact.
    @Test
    void diagnosticPollingMaskDoesNotOverrideProductionPolicy() {
        BitSet pollingCores = new BitSet();
        pollingCores.set(2);
        this.diagnosticOverride = FragmentControlPolicy.installDiagnosticPollingOverride(pollingCores);
        FragmentControlPolicy policy = new FragmentControlPolicy();
        recordBodySamples(policy, 100L, FragmentControlPolicy.EXPENSIVE_CONFIRMATION_SAMPLES);

        policy.completeBatch(4_096L, 1L, 2);

        assertTrue(policy.bodyCostSamplingEnabled());
        assertTrue(policy.activePollingAllowed(2));
        assertFalse(policy.activePollingAllowed(3));
        assertEquals(FragmentControlPolicy.Mode.STAGED, policy.mode());

        pollingCores.set(3);
        assertFalse(policy.activePollingAllowed(3));
    }

    /// Verifies a fixed batch target retains the existing eligible cap and floor.
    @Test
    void diagnosticBatchRemainsWithinEligibleBounds() {
        this.diagnosticOverride =
                FragmentControlPolicy.installDiagnosticOverride(FragmentControlPolicy.Mode.DIRECT, 32L);
        FragmentControlPolicy policy = new FragmentControlPolicy();

        assertEquals(32L, policy.completeBatch(64L, 0L, 0));
        assertEquals(17L, policy.completeBatch(17L, 0L, 0));
        assertEquals(2L, policy.completeBatch(1L, 0L, 0));
    }

    /// Verifies setup cannot silently replace an override owned by another diagnostic trial.
    @Test
    void rejectsConcurrentDiagnosticOverride() {
        this.diagnosticOverride =
                FragmentControlPolicy.installDiagnosticOverride(FragmentControlPolicy.Mode.DIRECT, 32L);

        assertThrows(
                IllegalStateException.class,
                () -> FragmentControlPolicy.installDiagnosticOverride(FragmentControlPolicy.Mode.STAGED, 32L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FragmentControlPolicy.DiagnosticOverride(FragmentControlPolicy.Mode.DIRECT, 1L));
    }

    /// Adds the same valid sample repeatedly without exposing estimator setup outside the package.
    private static void recordBodySamples(FragmentControlPolicy policy, long elapsedNs, int count) {
        for (int i = 0; i < count; i++) {
            policy.recordBodyCost(elapsedNs);
        }
    }
}
