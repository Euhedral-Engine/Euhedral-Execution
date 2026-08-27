package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.config.FragmentDecisionWeights;
import io.euhedral_execution.core.config.FragmentDecisionWeights.BodyCostWeights;
import io.euhedral_execution.core.config.FragmentDecisionWeights.IdlePolicy;
import io.euhedral_execution.core.config.FragmentDecisionWeights.ParetoWeights;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Dedicated runtime action parity test for FragmentDecisionTree.
 *
 * Verifies runtime action mapping:
 *   m <= 0 -> shouldCacheExecute == false (participate)
 *   m > 0  -> shouldCacheExecute == true  (withdraw to CACHE)
 *
 * Requirements:
 * - Forked JVM with system property -Deuhedral.fragment.cacheExecutePath=true
 * - Exact zero boundary parity (m == 0.0 -> false)
 * - Identical named ParetoWeights mapping to Python LogicalWeights
 */
class FragmentDecisionTreeRuntimeParityTest {

    private static FragmentDecisionTree createTreeWithWeights(ParetoWeights weights, double smoothedBodyCostNs) {
        FragmentDecisionWeights decisionWeights =
                new FragmentDecisionWeights(BodyCostWeights.DEFAULTS, IdlePolicy.DEFAULT, weights);
        FragmentDecisionTree tree = new FragmentDecisionTree(decisionWeights, null, 0, 0);

        // Pre-populate body cost window to set smoothedBodyCostNs
        for (int i = 0; i < 32; i++) {
            tree.recordBodyCost((long) smoothedBodyCostNs);
        }
        return tree;
    }

    @Test
    @DisplayName("Exact zero boundary m = 0.0 must select PARTICIPATE (shouldCacheExecute == false)")
    void testExactZeroBoundary() {
        // Construct weights and inputs such that m = phrFactor * P / (K*(K-1)) - workerFactor == 0.0 exactly.
        // Let phrWeight = 2.0, activeWorkersWeight = 1.0, contention = 0, body = 0, R = 0.
        // K = 2 -> K*(K-1) = 2.0. Let P = 1.0.
        // phrFactor = 2.0. workerFactor = 1.0.
        // m = 2.0 * 1.0 / 2.0 - 1.0 = 0.0.
        ParetoWeights zeroWeights = new ParetoWeights(
                1.0, // activeWorkersWeight (w4)
                0.0, // contentionPhrWeight (w1)
                0.0, // contentionWorkersWeight (w5)
                2.0, // phrWeight (w0)
                0.0, // bodyPhrWeight (w2)
                0.0, // bodyWorkersWeight (w6)
                0.0, // registeredWorkersPhrWeight (w3)
                0.0 // registeredActiveWorkersWeight (w7)
                );

        FragmentDecisionTree tree = createTreeWithWeights(zeroWeights, 0.0);

        double contention = 0.0;
        long productiveHandles = 1L; // P = 1
        int registeredWorkers = 23;
        int workerRank = 2; // K = 2

        boolean shouldCache = tree.shouldCacheExecute(contention, productiveHandles, registeredWorkers, workerRank);

        // At m == 0.0, action must be PARTICIPATE (shouldCacheExecute == false)
        assertFalse(shouldCache, "Exact zero boundary m = 0.0 must evaluate to false (participate)");
    }

    @Test
    @DisplayName("Near-zero negative marginal m = -1e-12 must select PARTICIPATE (false)")
    void testNearZeroNegativeMarginal() {
        // m = (2.0 - 1e-12) * 1.0 / 2.0 - 1.0 = 1.0 - 0.5e-12 - 1.0 = -0.5e-12 < 0
        ParetoWeights weights = new ParetoWeights(
                1.0, // activeWorkersWeight
                0.0,
                0.0,
                2.0 - 1e-12, // phrWeight
                0.0,
                0.0,
                0.0,
                0.0);

        FragmentDecisionTree tree = createTreeWithWeights(weights, 0.0);
        boolean shouldCache = tree.shouldCacheExecute(0.0, 1L, 23, 2);
        assertFalse(shouldCache, "Negative marginal must evaluate to false");
    }

    @Test
    @DisplayName("Near-zero positive marginal m = +1e-12 must select CACHE (true)")
    void testNearZeroPositiveMarginal() {
        // m = (2.0 + 1e-12) * 1.0 / 2.0 - 1.0 = +0.5e-12 > 0
        ParetoWeights weights = new ParetoWeights(
                1.0, // activeWorkersWeight
                0.0,
                0.0,
                2.0 + 1e-12, // phrWeight
                0.0,
                0.0,
                0.0,
                0.0);

        FragmentDecisionTree tree = createTreeWithWeights(weights, 0.0);
        boolean shouldCache = tree.shouldCacheExecute(0.0, 1L, 23, 2);
        assertTrue(shouldCache, "Positive marginal must evaluate to true");
    }

    @ParameterizedTest(name = "M8 structure: c={0}, bodyNs={1}, P={2}, R={3}, K={4}, expectedCache={5}")
    @CsvSource({
        // c, bodyNs, P, R, K, expectedCache
        // Case 1: participate dominated (high worker factor, low phr)
        "0.5, 500.0, 2, 23, 10, false",
        // Case 2: cache dominated (very high P, low active workers)
        "0.1, 100.0, 100, 7, 2, true",
        // Case 3: K=1 rank boundary (always false)
        "0.5, 500.0, 100, 23, 1, false",
        // Case 4: R=1 boundary (always false)
        "0.5, 500.0, 100, 1, 1, false",
        // Case 5: P=0 (always true if K > 1)
        "0.5, 500.0, 0, 23, 4, true",
    })
    void testSyntheticCoordinatesParity(
            double contention,
            double bodyNs,
            long productiveHandles,
            int registeredWorkers,
            int workerRank,
            boolean expectedCache) {
        // Standard M8 fitted candidate weights
        ParetoWeights m8Weights = new ParetoWeights(
                0.85, // activeWorkersWeight (w4)
                0.15, // contentionPhrWeight (w1)
                0.25, // contentionWorkersWeight (w5)
                1.20, // phrWeight (w0)
                0.05, // bodyPhrWeight (w2)
                0.10, // bodyWorkersWeight (w6)
                0.02, // registeredWorkersPhrWeight (w3)
                0.04 // registeredActiveWorkersWeight (w7)
                );

        FragmentDecisionTree tree = createTreeWithWeights(m8Weights, bodyNs);
        boolean shouldCache = tree.shouldCacheExecute(contention, productiveHandles, registeredWorkers, workerRank);

        assertEquals(expectedCache, shouldCache);
    }

    @Test
    @DisplayName("Candidate lattice M2, M4, M6, M8 weights parity")
    void testCandidateLatticeStructuresParity() {
        // M2 candidate weights: only w0 (phrWeight) and w4 (activeWorkersWeight) active
        ParetoWeights m2 = new ParetoWeights(0.5, 0.0, 0.0, 1.5, 0.0, 0.0, 0.0, 0.0);
        FragmentDecisionTree treeM2 = createTreeWithWeights(m2, 200.0);
        // K=3 => K*(K-1)=6. P=4 => q=4/6 = 0.6667. A=1.5 => A*q = 1.0. B=0.5. m = 1.0 - 0.5 = 0.5 > 0 => CACHE
        assertTrue(treeM2.shouldCacheExecute(0.3, 4L, 23, 3));
        // P=1 => q=1/6. A*q = 0.25. B=0.5 => m = -0.25 <= 0 => PARTICIPATE
        assertFalse(treeM2.shouldCacheExecute(0.3, 1L, 23, 3));

        // M4-C candidate weights: w0, w1, w4, w5
        ParetoWeights m4c = new ParetoWeights(0.4, 0.2, 0.3, 1.0, 0.0, 0.0, 0.0, 0.0);
        FragmentDecisionTree treeM4C = createTreeWithWeights(m4c, 200.0);
        // c=0.5 => A = 1.0 + 0.2*0.5 = 1.1. B = 0.4 + 0.3*0.5 = 0.55.
        // K=4 => K*(K-1)=12. P=6 => q=0.5. A*q = 0.55. B=0.55. m = 0.55 - 0.55 = 0.0 => exact 0 => false
        assertFalse(treeM4C.shouldCacheExecute(0.5, 6L, 23, 4));
        // P=7 => q=7/12. A*q = 0.641667 > B(0.55) => m > 0 => true
        assertTrue(treeM4C.shouldCacheExecute(0.5, 7L, 23, 4));
    }
}
