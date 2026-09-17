package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class FragmentDecisionTreeTest {

    private static final int TEST_CORE = 2;
    private static final int TEST_SOCKET = 1;

    private static FragmentDecisionTree createDefaultTree(FragmentObserver observer) {
        return new FragmentDecisionTree(observer, TEST_CORE, TEST_SOCKET);
    }

    private static void populateBodyCosts(FragmentDecisionTree tree, int count, long valueNs) {
        for (int i = 0; i < count; i++) {
            tree.recordBodyCost(valueNs);
        }
    }

    @Test
    void recordExecution_ignoresNonPositiveElapsedAndFrames() {
        FragmentDecisionTree tree = createDefaultTree(null);

        tree.recordExecution(0L, 10L);
        assertEquals(0.0, tree.serviceTimeNs());

        tree.recordExecution(-100L, 10L);
        assertEquals(0.0, tree.serviceTimeNs());

        tree.recordExecution(100L, 0L);
        assertEquals(0.0, tree.serviceTimeNs());

        tree.recordExecution(100L, -5L);
        assertEquals(0.0, tree.serviceTimeNs());
    }

    @Test
    void recordExecution_initializesServiceTimeOnFirstSample() {
        FragmentDecisionTree tree = createDefaultTree(null);

        tree.recordExecution(1_000L, 10L);
        assertEquals(100.0, tree.serviceTimeNs(), 1e-9);
    }

    @Test
    void recordExecution_appliesEwmaSmoothing() {
        FragmentDecisionTree tree = createDefaultTree(null);

        // First sample: 800 / 10 = 80.0
        tree.recordExecution(800L, 10L);
        assertEquals(80.0, tree.serviceTimeNs(), 1e-9);

        // Second sample: 1600 / 10 = 160.0. EWMA: 80.0 + (160.0 - 80.0) / 8.0 = 90.0
        tree.recordExecution(1_600L, 10L);
        assertEquals(90.0, tree.serviceTimeNs(), 1e-9);

        // Third sample: invalid sample ignored
        tree.recordExecution(0L, 10L);
        assertEquals(90.0, tree.serviceTimeNs(), 1e-9);

        // Fourth sample: 800 / 10 = 80.0. EWMA: 90.0 + (80.0 - 90.0) / 8.0 = 88.75
        tree.recordExecution(800L, 10L);
        assertEquals(88.75, tree.serviceTimeNs(), 1e-9);
    }

    @Test
    void recordBodyCost_ignoresNonPositiveElapsed() {
        FragmentDecisionTree tree = createDefaultTree(null);

        tree.recordBodyCost(0L);
        tree.recordBodyCost(-50L);

        // Less than 32 samples will keep execution path returning DIRECT
        assertEquals(ExecutionPath.DIRECT, tree.executionPath(1L, 1L, 2L, 2L, 4, 100L));
    }

    @Test
    void recordBodyCost_historyCountCapsAtIntegerMaxValue() throws Exception {
        FragmentDecisionTree tree = createDefaultTree(null);

        Field countField = FragmentDecisionTree.class.getDeclaredField("bodyCostHistoryCount");
        countField.setAccessible(true);
        countField.setInt(tree, Integer.MAX_VALUE);

        // Record a sample when count is already Integer.MAX_VALUE
        tree.recordBodyCost(100L);

        assertEquals(Integer.MAX_VALUE, countField.getInt(tree));
    }

    @Test
    void completeBatch_initialAndNoServiceTime() {
        FragmentDecisionTree tree = createDefaultTree(null);

        // Without service time (serviceTimeNs == 0.0), completeBatch keeps batchSize at 2 (bounded by eligibleCap)
        assertEquals(2L, tree.completeBatch(16L));
        assertEquals(2L, tree.completeBatch(2L));
        assertEquals(2L, tree.completeBatch(1L)); // cap < 2 is treated as cap = 2
    }

    @Test
    void completeBatch_scalesWithDirectWorkTarget() {
        FragmentDecisionTree tree = createDefaultTree(null);

        // DIRECT target is 250_000 ns. Set service time to 1_000 ns.
        // raw = 250_000 / 1_000 = 250. highestOneBit(250) = 128. Desired = 128.
        tree.recordExecution(10_000L, 10L); // serviceTime = 1_000 ns
        assertEquals(1000.0, tree.serviceTimeNs(), 1e-9);

        // Batch starts at 2. It grows via doubling: 2 -> 4 -> 8 -> 16 -> 32 -> 64 -> 128 -> 128
        assertEquals(4L, tree.completeBatch(1024L));
        assertEquals(8L, tree.completeBatch(1024L));
        assertEquals(16L, tree.completeBatch(1024L));
        assertEquals(32L, tree.completeBatch(1024L));
        assertEquals(64L, tree.completeBatch(1024L));
        assertEquals(128L, tree.completeBatch(1024L));
        assertEquals(128L, tree.completeBatch(1024L));
    }

    @Test
    void completeBatch_scalesWithStagedWorkTarget() {
        FragmentDecisionTree tree = createDefaultTree(null);
        populateBodyCosts(tree, 32, 100L);

        assertEquals(ExecutionPath.STAGED, tree.executionPath(1L, 1L, 2L, 2L, 4, 900_000L));

        // STAGED target is 8_000_000 ns. Set service time to 10_000 ns.
        // raw = 8_000_000 / 10_000 = 800. highestOneBit(800) = 512. Desired = 512.
        tree.recordExecution(100_000L, 10L); // serviceTime = 10_000 ns

        long batch = 2L;
        while (batch < 512L) {
            batch = tree.completeBatch(1024L);
        }
        assertEquals(512L, batch);
        assertEquals(512L, tree.completeBatch(1024L));
    }

    @Test
    void completeBatch_respectsEligibleCapAndClamping() {
        FragmentDecisionTree tree = createDefaultTree(null);

        // serviceTime = 100 ns -> raw = 250_000 / 100 = 2500 -> highestOneBit = 2048
        tree.recordExecution(1_000L, 10L);

        // Cap at 16
        long batch = tree.completeBatch(16L);
        assertEquals(4L, batch);
        batch = tree.completeBatch(16L);
        assertEquals(8L, batch);
        batch = tree.completeBatch(16L);
        assertEquals(16L, batch);
        batch = tree.completeBatch(16L);
        assertEquals(16L, batch); // Does not exceed cap
    }

    @Test
    void completeBatch_handlesExtremelyHighServiceTime() {
        FragmentDecisionTree tree = createDefaultTree(null);

        // Very high service time (e.g. 10_000_000_000 ns): raw becomes 0, Math.max(2L, raw) = 2
        for (int i = 0; i < 40; i++) {
            tree.recordExecution(100_000_000_000L, 10L);
        }

        assertEquals(2L, tree.completeBatch(1024L));
    }

    @Test
    void completeBatch_stepsDownBatchSizeWhenServiceTimeIncreases() {
        FragmentDecisionTree tree = createDefaultTree(null);

        // First ramp up to 64
        tree.recordExecution(10_000L, 10L); // serviceTime = 1000 ns (desired = 128)
        while (tree.completeBatch(64L) < 64L) {
            // ramp up
        }

        // Drastically increase service time to 250_000 ns (desired = 2)
        for (int i = 0; i < 40; i++) {
            tree.recordExecution(2_500_000L, 10L);
        }

        // Halves down: 64 -> 32 -> 16 -> 8 -> 4 -> 2 -> 2
        assertEquals(32L, tree.completeBatch(1024L));
        assertEquals(16L, tree.completeBatch(1024L));
        assertEquals(8L, tree.completeBatch(1024L));
        assertEquals(4L, tree.completeBatch(1024L));
        assertEquals(2L, tree.completeBatch(1024L));
        assertEquals(2L, tree.completeBatch(1024L));
    }

    @Test
    void completeBatch_oddBatchStepDownRoundsUp() {
        FragmentDecisionTree tree = createDefaultTree(null);

        // Set batch to 3 using capping
        tree.recordExecution(10_000L, 10L); // desired = 128
        assertEquals(3L, tree.completeBatch(3L)); // clamped to cap 3

        // Now with desired = 2, minimum for batchSize 3 is (3 >>> 1) + (3 & 1) = 1 + 1 = 2
        for (int i = 0; i < 40; i++) {
            tree.recordExecution(2_500_000L, 10L);
        }
        assertEquals(2L, tree.completeBatch(1024L));
    }

    @Test
    void saturatingDouble_handlesNormalAndOverflowValues() {
        assertEquals(20L, FragmentDecisionTree.saturatingDouble(10L));
        assertEquals(Long.MAX_VALUE - 1, FragmentDecisionTree.saturatingDouble(Long.MAX_VALUE / 2L));
        assertEquals(Long.MAX_VALUE, FragmentDecisionTree.saturatingDouble(Long.MAX_VALUE / 2L + 1));
        assertEquals(Long.MAX_VALUE, FragmentDecisionTree.saturatingDouble(Long.MAX_VALUE));
    }

    @Test
    void reset_restoresAllInternalState() {
        FragmentDecisionTree tree = createDefaultTree(null);

        // Mutate state
        tree.recordExecution(10_000L, 10L);
        populateBodyCosts(tree, 32, 200L);
        tree.completeBatch(16L);

        assertTrue(tree.serviceTimeNs() > 0.0);

        // Reset
        tree.reset();

        assertEquals(0.0, tree.serviceTimeNs());
        assertEquals(2L, tree.completeBatch(16L));
    }

    @Test
    void executionPath_worksWithoutObserver() {
        FragmentDecisionTree tree = createDefaultTree(null);
        populateBodyCosts(tree, 32, 100L);

        ExecutionPath path = tree.executionPath(1L, 1L, 2L, 2L, 4, 100L);
        assertEquals(ExecutionPath.DIRECT, path);
    }

    @Test
    void executionPath_withProductiveHandles() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);
        populateBodyCosts(tree, 32, 1L);

        // Registered workers <= 1 -> DIRECT
        assertEquals(ExecutionPath.DIRECT, tree.executionPath(2L, 1L, 2L, 2L, 1, 100L));

        // A model DEFAULT continues through the existing low-contention branch.
        assertEquals(ExecutionPath.DIRECT, tree.executionPath(3L, 1L, 2L, 2L, 4, 100L));

        // High contention branch.
        assertEquals(ExecutionPath.STAGED, tree.executionPath(4L, 1L, 2L, 2L, 4, 850_001L));
    }

    @Test
    void shouldIdle_appliesPhysicalGuardsBeforeTheRuntimeModel() {
        FragmentDecisionTree tree = createDefaultTree(null);
        populateBodyCosts(tree, 32, 1L);

        assertFalse(tree.shouldIdle(800_000L, 2L, 4, 1));
        assertFalse(tree.shouldIdle(800_000L, 2L, 4, 0));
        assertFalse(tree.shouldIdle(800_000L, 2L, 1, 2));
        assertTrue(tree.shouldIdle(800_000L, 0L, 4, 2));

        assertEquals(
                ParticipationLogisticModel.shouldIdle(2, 1L, 7, tree.smoothedBodyCostNs(), 0.431857),
                tree.shouldIdle(431_857L, 1L, 7, 2));
    }
}
