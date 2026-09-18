package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.config.IdlePolicy;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class FragmentDecisionTreeTest {

    private static FragmentDecisionTree createDefaultTree() {
        return new FragmentDecisionTree(IdlePolicy.DEFAULT, 1_000L, 1_000L);
    }

    private static void populateBodyCosts(FragmentDecisionTree tree, int count, long valueNs) {
        for (int i = 0; i < count; i++) {
            tree.recordBodyCost(valueNs);
        }
    }

    @Test
    void recordExecution_ignoresNonPositiveElapsedAndFrames() {
        FragmentDecisionTree tree = createDefaultTree();

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
        FragmentDecisionTree tree = createDefaultTree();

        tree.recordExecution(1_000L, 10L);
        assertEquals(100.0, tree.serviceTimeNs(), 1e-9);
    }

    @Test
    void recordExecution_appliesEwmaSmoothing() {
        FragmentDecisionTree tree = createDefaultTree();

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
        FragmentDecisionTree tree = createDefaultTree();

        tree.recordBodyCost(0L);
        tree.recordBodyCost(-50L);

        // Less than 32 samples will keep execution path returning DIRECT
        assertEquals(ExecutionPath.DIRECT, tree.selectExecutionPath(2L, 2L, 4, 100L));
    }

    @Test
    void recordBodyCost_historyCountCapsAtIntegerMaxValue() throws Exception {
        FragmentDecisionTree tree = createDefaultTree();

        Field countField = FragmentDecisionTree.class.getDeclaredField("bodyCostHistoryCount");
        countField.setAccessible(true);
        countField.setInt(tree, Integer.MAX_VALUE);

        // Record a sample when count is already Integer.MAX_VALUE
        tree.recordBodyCost(100L);

        assertEquals(Integer.MAX_VALUE, countField.getInt(tree));
    }

    @Test
    void recordBodyCostUpdatesOnlyAtCompleteWindowsUsingTheSecondMinimum() {
        FragmentDecisionTree tree = createDefaultTree();

        populateBodyCosts(tree, 31, 9L);
        assertEquals(0.0, tree.smoothedBodyCostNs());

        tree.recordBodyCost(3L);
        assertEquals(9.0, tree.smoothedBodyCostNs());

        populateBodyCosts(tree, 31, 7L);
        assertEquals(9.0, tree.smoothedBodyCostNs());

        tree.recordBodyCost(5L);
        assertEquals(7.0, tree.smoothedBodyCostNs());
    }

    @Test
    void recordBodyCostCountsDuplicateMinimaAsTwoObservations() {
        FragmentDecisionTree tree = createDefaultTree();

        tree.recordBodyCost(2L);
        tree.recordBodyCost(2L);
        populateBodyCosts(tree, 30, 11L);

        assertEquals(2.0, tree.smoothedBodyCostNs());
    }

    @Test
    void expensiveBodyCostRequiresTwoConsecutiveWindows() throws Exception {
        FragmentDecisionTree tree = createDefaultTree();
        long expensiveThreshold = longField(tree, "expensiveBodyCostThreshold");
        long expensiveSample = expensiveThreshold + 1L;

        populateBodyCosts(tree, 32, expensiveSample);
        assertEquals(0.0, tree.smoothedBodyCostNs());

        populateBodyCosts(tree, 32, expensiveSample);
        assertEquals((double) expensiveSample, tree.smoothedBodyCostNs());

        populateBodyCosts(tree, 32, 1L);
        assertEquals(1.0, tree.smoothedBodyCostNs());
    }

    @Test
    void resetRequiresAFreshBodyCostWindow() {
        FragmentDecisionTree tree = createDefaultTree();
        populateBodyCosts(tree, 32, 1L);
        assertEquals(1.0, tree.smoothedBodyCostNs());

        tree.reset();
        populateBodyCosts(tree, 31, 2L);

        assertEquals(0.0, tree.smoothedBodyCostNs());
        assertFalse(tree.hasBodyCostHistory());
        tree.recordBodyCost(2L);
        assertEquals(2.0, tree.smoothedBodyCostNs());
    }

    @Test
    void completeBatch_initialAndNoServiceTime() {
        FragmentDecisionTree tree = createDefaultTree();

        // Without service time (serviceTimeNs == 0.0), completeBatch keeps batchSize at 2 (bounded by eligibleCap)
        assertEquals(2L, tree.completeBatch(16L));
        assertEquals(2L, tree.completeBatch(2L));
        assertEquals(2L, tree.completeBatch(1L)); // cap < 2 is treated as cap = 2
    }

    @Test
    void completeBatch_scalesWithDirectWorkTarget() {
        FragmentDecisionTree tree = createDefaultTree();

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
        FragmentDecisionTree tree = createDefaultTree();
        populateBodyCosts(tree, 32, 100L);

        assertEquals(ExecutionPath.STAGED, tree.selectExecutionPath(2L, 2L, 4, 900_000L));

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
        FragmentDecisionTree tree = createDefaultTree();

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
        FragmentDecisionTree tree = createDefaultTree();

        // Very high service time (e.g. 10_000_000_000 ns): raw becomes 0, Math.max(2L, raw) = 2
        for (int i = 0; i < 40; i++) {
            tree.recordExecution(100_000_000_000L, 10L);
        }

        assertEquals(2L, tree.completeBatch(1024L));
    }

    @Test
    void completeBatch_stepsDownBatchSizeWhenServiceTimeIncreases() {
        FragmentDecisionTree tree = createDefaultTree();

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
        FragmentDecisionTree tree = createDefaultTree();

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
    void completeBatchAppliesACollapsedCapImmediately() {
        FragmentDecisionTree tree = createDefaultTree();
        tree.recordExecution(10_000L, 10L);
        while (tree.completeBatch(64L) < 64L) {
            // Reach a batch larger than the new cap.
        }

        assertEquals(3L, tree.completeBatch(3L));
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
        FragmentDecisionTree tree = createDefaultTree();

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
        FragmentDecisionTree tree = createDefaultTree();
        populateBodyCosts(tree, 32, 100L);

        ExecutionPath path = tree.selectExecutionPath(2L, 2L, 4, 100L);
        assertEquals(ExecutionPath.DIRECT, path);
    }

    @Test
    void executionPath_withProductiveHandles() {
        FragmentDecisionTree tree = createDefaultTree();
        populateBodyCosts(tree, 32, 1L);

        // Registered workers <= 1 -> DIRECT
        assertEquals(ExecutionPath.DIRECT, tree.selectExecutionPath(2L, 2L, 1, 100L));

        // A model DEFAULT continues through the existing low-contention branch.
        assertEquals(ExecutionPath.DIRECT, tree.selectExecutionPath(2L, 2L, 4, 100L));

        // High contention branch.
        assertEquals(ExecutionPath.STAGED, tree.selectExecutionPath(2L, 2L, 4, 850_001L));
    }

    @Test
    void executionPathUsesInclusiveContentionAndBodyThresholds() {
        FragmentDecisionTree tree = new FragmentDecisionTree(IdlePolicy.DEFAULT, 1_000L, 10L);
        populateBodyCosts(tree, 32, 10L);

        assertEquals(ExecutionPath.DIRECT, tree.selectExecutionPath(1L, 2L, 4, 850_000L));
        assertEquals(ExecutionPath.STAGED, tree.selectExecutionPath(1L, 2L, 4, 850_001L));

        FragmentDecisionTree costlyTree = new FragmentDecisionTree(IdlePolicy.DEFAULT, 1_000L, 9L);
        populateBodyCosts(costlyTree, 32, 10L);
        assertEquals(ExecutionPath.STAGED, costlyTree.selectExecutionPath(1L, 2L, 4, 850_000L));
    }

    @Test
    void shouldIdle_appliesPhysicalGuardsBeforeTheRuntimeModel() {
        FragmentDecisionTree tree = createDefaultTree();
        populateBodyCosts(tree, 32, 1L);

        assertFalse(tree.shouldIdle(800_000L, 2L, 4, 1));
        assertFalse(tree.shouldIdle(800_000L, 2L, 4, 0));
        assertFalse(tree.shouldIdle(800_000L, 2L, 1, 2));
        assertTrue(tree.shouldIdle(800_000L, 0L, 4, 2));

        assertEquals(
                ParticipationLogisticModel.shouldIdle(2, 1L, 7, tree.smoothedBodyCostNs(), 0.431857),
                tree.shouldIdle(431_857L, 1L, 7, 2));
    }

    @Test
    void idleDecisionPreservesTheSelectedExecutionPathForBatchSizing() {
        FragmentDecisionTree tree = createDefaultTree();
        populateBodyCosts(tree, 32, 1L);
        assertEquals(ExecutionPath.STAGED, tree.selectExecutionPath(1L, 2L, 4, 900_000L));
        assertTrue(tree.shouldIdle(900_000L, 0L, 4, 2));
        tree.recordExecution(1_000L, 1L);

        long batch = 2L;
        while (batch < 256L) {
            batch = tree.completeBatch(1_024L);
        }

        assertEquals(256L, batch);
    }

    private static long longField(FragmentDecisionTree tree, String name) throws Exception {
        Field field = FragmentDecisionTree.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(tree);
    }
}
