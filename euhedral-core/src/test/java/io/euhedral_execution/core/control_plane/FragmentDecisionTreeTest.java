package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import io.euhedral_execution.core.config.FragmentDecisionWeights;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.BodyCostWeights;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.IdlePolicy;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class FragmentDecisionTreeTest {

    private static final int TEST_CORE = 2;
    private static final int TEST_SOCKET = 1;

    private static FragmentDecisionTree createDefaultTree(FragmentObserver observer) {
        return new FragmentDecisionTree(FragmentDecisionWeights.DEFAULT, observer, TEST_CORE, TEST_SOCKET);
    }

    private static FragmentDecisionWeights createCustomWeights(BodyCostWeights bodyWeights, IdlePolicy idlePolicy) {
        return new FragmentDecisionWeights(bodyWeights, idlePolicy);
    }

    private static void populateBodyCosts(FragmentDecisionTree tree, int count, long valueNs) {
        for (int i = 0; i < count; i++) {
            tree.recordBodyCost(valueNs);
        }
    }

    @Test
    void constructorThrowsOnNullDecisionWeights() {
        assertThrows(NullPointerException.class, () -> new FragmentDecisionTree(null, null, 0, 0));
    }

    @Test
    void initialStateIsCleanAndReset() {
        FragmentDecisionTree tree = createDefaultTree(null);

        assertEquals(0.0, tree.serviceTimeNs());
        assertFalse(tree.missRequiresPark());

        // Initial batch size starts at 2
        assertEquals(2L, tree.completeBatch(16L));

        // When upstream handles is 0, executionPath returns SKIP_THEN_DIRECT
        assertEquals(ExecutionPath.SKIP_THEN_DIRECT, tree.executionPath(1L, 1L, 0L, 4, 100L));
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
        assertEquals(ExecutionPath.DIRECT, tree.executionPath(1L, 1L, 2L, 4, 100L));
    }

    @Test
    void recordBodyCost_doesNotUpdateEstimateBefore32Samples() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);

        // Provide 31 samples
        populateBodyCosts(tree, 31, 500L);
        assertFalse(tree.hasBodyCostHistory());

        // With history count 31 < 32 (BODY_COST_MIN_HISTORY), executionPath returns DIRECT early without calling
        // observer
        assertEquals(ExecutionPath.DIRECT, tree.executionPath(1L, 1L, 2L, 4, 100L));
        verify(observer, never())
                .execBranchDecision(
                        anyInt(), anyInt(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyDouble());
    }

    @Test
    void recordBodyCost_updatesEstimateAt32SamplesWithSecondMinimum() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);

        // Provide 32 samples: sample 0 is 100ns, sample 1 is 200ns, rest are 500ns
        tree.recordBodyCost(100L);
        tree.recordBodyCost(200L);
        for (int i = 2; i < 32; i++) {
            tree.recordBodyCost(500L);
        }
        assertTrue(tree.hasBodyCostHistory());
        assertEquals(200.0, tree.smoothedBodyCostNs());

        // Now history count is 32 >= BODY_COST_MIN_HISTORY. Second minimum (200.0) should be active.
        tree.executionPath(1L, 1L, 2L, 4, 100L);
        verify(observer)
                .execBranchDecision(
                        eq(TEST_CORE),
                        eq(TEST_SOCKET),
                        eq(1L),
                        eq(1L),
                        eq(0), // contention <= xsContention (650_000)
                        anyInt(),
                        eq(100L),
                        eq(200.0));
    }

    @Test
    void recordBodyCost_withMultipleIdenticalMinimums() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);

        // All 32 samples are 75ns: minimum = 75.0, secondMinimum = 75.0
        populateBodyCosts(tree, 32, 75L);

        tree.executionPath(1L, 1L, 2L, 4, 100L);
        verify(observer)
                .execBranchDecision(
                        eq(TEST_CORE), eq(TEST_SOCKET), eq(1L), eq(1L), anyInt(), anyInt(), eq(100L), eq(75.0));
    }

    @Test
    void recordBodyCost_expensiveWorkRequiresTwoConsecutiveWindows() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);

        // 32 samples of very expensive work (e.g. 50_000_000 ns) exceeding maxBodyCostThreshold
        populateBodyCosts(tree, 32, 50_000_000L);

        // Window 1: expensiveConfirmationWindows = 1 < 2, smoothedBodyCostNs remains 0.0
        tree.executionPath(1L, 1L, 2L, 4, 100L);
        verify(observer)
                .execBranchDecision(
                        eq(TEST_CORE), eq(TEST_SOCKET), eq(1L), eq(1L), anyInt(), anyInt(), eq(100L), eq(0.0));

        reset(observer);

        // Window 2: Provide another 32 samples of expensive work
        populateBodyCosts(tree, 32, 50_000_000L);

        // expensiveConfirmationWindows reaches 2, smoothedBodyCostNs updates to 50_000_000.0
        tree.executionPath(2L, 1L, 2L, 4, 100L);
        verify(observer)
                .execBranchDecision(
                        eq(TEST_CORE), eq(TEST_SOCKET), eq(2L), eq(1L), anyInt(), anyInt(), eq(100L), eq(50_000_000.0));

        reset(observer);

        // Window 3: Provide a 3rd window of expensive work (covers expensiveConfirmationWindows >= 2 branch)
        populateBodyCosts(tree, 32, 50_000_000L);
        tree.executionPath(3L, 1L, 2L, 4, 100L);
        verify(observer)
                .execBranchDecision(
                        eq(TEST_CORE), eq(TEST_SOCKET), eq(3L), eq(1L), anyInt(), anyInt(), eq(100L), eq(50_000_000.0));
    }

    @Test
    void recordBodyCost_inexpensiveWindowResetsExpensiveConfirmationCount() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);

        // Window 1: expensive work (expensiveConfirmationWindows becomes 1)
        populateBodyCosts(tree, 32, 50_000_000L);

        // Window 2: inexpensive work (resets expensiveConfirmationWindows to 0 and updates smoothedBodyCostNs
        // immediately)
        populateBodyCosts(tree, 32, 150L);

        tree.executionPath(1L, 1L, 2L, 4, 100L);
        verify(observer)
                .execBranchDecision(
                        eq(TEST_CORE), eq(TEST_SOCKET), eq(1L), eq(1L), anyInt(), anyInt(), eq(100L), eq(150.0));
    }

    @Test
    void recordBodyCost_circularBufferUpdatesEvery32Samples() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = new FragmentDecisionTree(
                createCustomWeights(new BodyCostWeights(10_000, 10_000, 10_000, 10_000), IdlePolicy.DEFAULT),
                observer,
                TEST_CORE,
                TEST_SOCKET);

        // Window 1: 32 samples of 100ns -> second minimum 100.0
        populateBodyCosts(tree, 32, 100L);
        tree.executionPath(1L, 1L, 2L, 4, 100L);
        verify(observer)
                .execBranchDecision(
                        eq(TEST_CORE), eq(TEST_SOCKET), eq(1L), eq(1L), anyInt(), anyInt(), eq(100L), eq(100.0));

        reset(observer);

        // Window 2: 32 samples of 300ns -> circular buffer overwrites, second minimum becomes 300.0
        populateBodyCosts(tree, 32, 300L);
        tree.executionPath(2L, 1L, 2L, 4, 100L);
        verify(observer)
                .execBranchDecision(
                        eq(TEST_CORE), eq(TEST_SOCKET), eq(2L), eq(1L), anyInt(), anyInt(), eq(100L), eq(300.0));
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
    void missRequiresPark_andRecordProgress_behavior() {
        FragmentDecisionTree tree = createDefaultTree(null);

        // 1 to 64 consecutive misses return false
        for (int i = 1; i <= FragmentDecisionTree.SPIN_MISSES; i++) {
            assertFalse(tree.missRequiresPark(), "Miss " + i + " should not require park");
        }

        // 65th miss returns true
        assertTrue(tree.missRequiresPark(), "65th miss should require park");

        // Subsequent misses continue to return true without overflow
        assertTrue(tree.missRequiresPark());
        assertTrue(tree.missRequiresPark());

        // recordProgress resets the miss streak
        tree.recordProgress();
        for (int i = 1; i <= FragmentDecisionTree.SPIN_MISSES; i++) {
            assertFalse(tree.missRequiresPark(), "Miss " + i + " after progress should not require park");
        }
        assertTrue(tree.missRequiresPark());
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

        assertEquals(ExecutionPath.STAGED, tree.executionPath(1L, 1L, 2L, 4, 900_000L));

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
        for (int i = 0; i < 70; i++) {
            tree.missRequiresPark();
        }
        tree.completeBatch(16L);

        assertTrue(tree.serviceTimeNs() > 0.0);
        assertTrue(tree.missRequiresPark());

        // Reset
        tree.reset();

        assertEquals(0.0, tree.serviceTimeNs());
        assertFalse(tree.missRequiresPark());
        assertEquals(2L, tree.completeBatch(16L));
    }

    @Test
    void idle_earlyExitsWhenUpstreamHandlesZero() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);
        populateBodyCosts(tree, 32, 100L);

        tree.idle(1L, 1L, 0L, 4, 1, 100L);
        tree.idle(1L, 1L, -1L, 4, 1, 100L);

        // Observer not called for upstream <= 0
        verify(observer, never())
                .idleBranchDecision(
                        anyInt(), anyInt(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyDouble());
    }

    @Test
    void idle_earlyExitsWhenRegisteredWorkersIsOneOrZero() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);
        populateBodyCosts(tree, 32, 100L);

        tree.idle(1L, 1L, 2L, 1, 1, 100L);
        tree.idle(1L, 1L, 2L, 0, 1, 100L);

        verify(observer, never())
                .idleBranchDecision(
                        anyInt(), anyInt(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyDouble());
    }

    @Test
    void idle_earlyExitsWhenBodyCostHistoryBelowMin() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);

        // Only 31 samples (< 32)
        populateBodyCosts(tree, 31, 100L);

        tree.idle(1L, 1L, 2L, 4, 1, 100L);
        verify(observer, never())
                .idleBranchDecision(
                        anyInt(), anyInt(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyDouble());
    }

    @Test
    void idle_earlyExitsWhenWorkerRankNonPositive() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);
        populateBodyCosts(tree, 32, 100L);

        tree.idle(1L, 1L, 2L, 4, 0, 100L);
        tree.idle(1L, 1L, 2L, 4, -1, 100L);

        verify(observer, never())
                .idleBranchDecision(
                        anyInt(), anyInt(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyDouble());
    }

    @Test
    void idle_evaluatesBothContentionBranchesAndNotifiesObserver() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);
        populateBodyCosts(tree, 32, 1L);

        assertEquals(-1L, tree.idle(1L, 1L, 2L, 4, 1, 850_000L));
        verify(observer).idleBranchDecision(TEST_CORE, TEST_SOCKET, 1L, 1L, 0, -1, 850_000L, 1.0);

        assertEquals(1_000L, tree.idle(2L, 1L, 2L, 4, 1, 850_001L));
        verify(observer).idleBranchDecision(TEST_CORE, TEST_SOCKET, 2L, 1L, 1, 0, 850_001L, 1.0);
    }

    @Test
    void idle_evaluatesAllBodyCostBranches() {
        assertIdleBodyCostDecision(new BodyCostWeights(10, 20, 30, 40), 32, 0);
        assertIdleBodyCostDecision(new BodyCostWeights(0, 10, 20, 30), 32, 1);
        assertIdleBodyCostDecision(new BodyCostWeights(0, 0, 10, 20), 32, 2);
        assertIdleBodyCostDecision(new BodyCostWeights(0, 0, 0, 10), 32, 3);
        assertIdleBodyCostDecision(new BodyCostWeights(0, 0, 0, 0), 64, 4);
    }

    private static void assertIdleBodyCostDecision(BodyCostWeights weights, int samples, int decision) {
        FragmentObserver observer = mock(FragmentObserver.class);
        IdlePolicy policy = new IdlePolicy(0, 0, 0, 0, 0);
        FragmentDecisionTree tree =
                new FragmentDecisionTree(createCustomWeights(weights, policy), observer, TEST_CORE, TEST_SOCKET);
        populateBodyCosts(tree, samples, 1L);

        tree.idle(1L, 1L, 2L, 4, 1, 900_000L);

        verify(observer).idleBranchDecision(TEST_CORE, TEST_SOCKET, 1L, 1L, 1, decision, 900_000L, 1.0);
    }

    @Test
    void idle_worksWithoutObserver() {
        FragmentDecisionTree tree = createDefaultTree(null);
        populateBodyCosts(tree, 32, 100L);

        // Does not throw NPE when observer is null
        tree.idle(1L, 1L, 2L, 4, 1, 100L);
    }

    @Test
    void executionPath_earlyExitsWhenUpstreamHandlesZero() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);
        populateBodyCosts(tree, 32, 100L);

        assertEquals(ExecutionPath.SKIP_THEN_DIRECT, tree.executionPath(1L, 1L, 0L, 4, 100L));
        verify(observer, never())
                .execBranchDecision(
                        anyInt(), anyInt(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyDouble());
    }

    @Test
    void executionPath_earlyExitsWhenRegisteredWorkersIsOneOrZero() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);
        populateBodyCosts(tree, 32, 100L);

        assertEquals(ExecutionPath.DIRECT, tree.executionPath(1L, 1L, 2L, 1, 100L));
        assertEquals(ExecutionPath.DIRECT, tree.executionPath(1L, 1L, 2L, 0, 100L));
        verify(observer, never())
                .execBranchDecision(
                        anyInt(), anyInt(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyDouble());
    }

    @Test
    void executionPath_earlyExitsWhenBodyCostHistoryBelowMin() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);
        populateBodyCosts(tree, 31, 100L);

        assertEquals(ExecutionPath.DIRECT, tree.executionPath(1L, 1L, 2L, 4, 100L));
        verify(observer, never())
                .execBranchDecision(
                        anyInt(), anyInt(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyDouble());
    }

    @Test
    void executionPath_transitionsFromSkipThenDirect() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);
        populateBodyCosts(tree, 32, 100L);

        assertEquals(ExecutionPath.SKIP_THEN_DIRECT, tree.executionPath(1L, 1L, 0L, 4, 100L));
        assertEquals(ExecutionPath.DIRECT, tree.executionPath(2L, 1L, 2L, 4, 100L));
        verify(observer, never())
                .execBranchDecision(
                        anyInt(), anyInt(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyDouble());
    }

    @Test
    void executionPath_evaluatesBothContentionBranchesAndNotifiesObserver() {
        FragmentObserver observer = mock(FragmentObserver.class);
        FragmentDecisionTree tree = createDefaultTree(observer);
        populateBodyCosts(tree, 32, 1L);

        assertEquals(ExecutionPath.DIRECT, tree.executionPath(1L, 1L, 2L, 4, 850_000L));
        verify(observer).execBranchDecision(TEST_CORE, TEST_SOCKET, 1L, 1L, 0, 0, 850_000L, 1.0);

        assertEquals(ExecutionPath.STAGED, tree.executionPath(2L, 1L, 2L, 4, 850_001L));
        verify(observer).execBranchDecision(TEST_CORE, TEST_SOCKET, 2L, 1L, 1, 0, 850_001L, 1.0);
    }

    @Test
    void executionPath_worksWithoutObserver() {
        FragmentDecisionTree tree = createDefaultTree(null);
        populateBodyCosts(tree, 32, 100L);

        ExecutionPath path = tree.executionPath(1L, 1L, 2L, 4, 100L);
        assertEquals(ExecutionPath.DIRECT, path);
    }
}
