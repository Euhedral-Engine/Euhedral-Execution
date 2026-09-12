package calibration.comparisons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.schema.ComparisonSet;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.RunArtifacts;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ThroughputResult;
import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.ComparisonConfig;
import calibration.config.ComparisonKeyConfig;
import calibration.config.ComparisonOptions;
import calibration.config.ComparisonStrategy;
import calibration.config.OriginType;
import calibration.config.TrialConfig;
import calibration.config.TrialOrigin;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComparisonPairPlannerTest {

    private CompletedRun createRun(String id, Integer candidateIndex, int workUnits, int parallelSources) {
        RunIdentity identity = new RunIdentity(id, "Name " + id, "group", 0, null, "/path/" + id);
        TrialOrigin origin =
                candidateIndex != null ? new TrialOrigin(OriginType.SWEEP, "base", 1738L, candidateIndex) : null;
        CalibrationBenchmarkConfig calConfig = new CalibrationBenchmarkConfig(
                List.of(1, 2),
                parallelSources,
                1,
                workUnits,
                false,
                1000L,
                5000L,
                null,
                io.euhedral_execution.core.config.FragmentDecisionWeights.DEFAULT,
                1024,
                true,
                true,
                true,
                true,
                true,
                true);

        TrialConfig trialConfig = new TrialConfig(
                id,
                "Name " + id,
                "group",
                null,
                null,
                null,
                null,
                null,
                true,
                origin,
                1,
                1,
                1,
                "1s",
                "1s",
                List.of(),
                null,
                calConfig);

        return new CompletedRun(
                identity,
                trialConfig,
                ThroughputResult.of(100.0, 1.0, "ops/s"),
                List.of(),
                RunArtifacts.standard("/path/" + id));
    }

    // ==========================================
    // BASELINE Tests
    // ==========================================

    @Test
    void testBaselineModeProducesOnePairPerCandidate() {
        CompletedRun baseline = createRun("base", null, 24, 2);
        CompletedRun candA = createRun("cand-a", null, 24, 2);
        CompletedRun candB = createRun("cand-b", null, 24, 2);
        CompletedRun candC = createRun("cand-c", null, 24, 2);

        ComparisonConfig config = new ComparisonConfig(
                ComparisonStrategy.BASELINE,
                ComparisonSet.ofSingle(calibration.comparisons.schema.RunReference.of("/path/base")),
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/cand-a"),
                        calibration.comparisons.schema.RunReference.of("/path/cand-b"),
                        calibration.comparisons.schema.RunReference.of("/path/cand-c"))),
                null,
                ComparisonOptions.DEFAULT,
                "/out");

        ComparisonPairPlan plan = ComparisonPairPlanner.plan(config, List.of(baseline), List.of(candA, candB, candC));

        assertEquals(ComparisonStrategy.BASELINE, plan.strategy());
        assertEquals(3, plan.pairs().size());

        assertEquals("base", plan.pairs().get(0).baseline().identity().trialId());
        assertEquals("cand-a", plan.pairs().get(0).candidate().identity().trialId());
        assertEquals(0, plan.pairs().get(0).pairIndex());
        assertNull(plan.pairs().get(0).key());

        assertEquals("base", plan.pairs().get(1).baseline().identity().trialId());
        assertEquals("cand-b", plan.pairs().get(1).candidate().identity().trialId());
        assertEquals(1, plan.pairs().get(1).pairIndex());

        assertEquals("base", plan.pairs().get(2).baseline().identity().trialId());
        assertEquals("cand-c", plan.pairs().get(2).candidate().identity().trialId());
        assertEquals(2, plan.pairs().get(2).pairIndex());
    }

    @Test
    void testBaselineModeRejectsMultipleBaselinesOrEmptyCandidates() {
        CompletedRun base1 = createRun("base1", null, 24, 2);
        CompletedRun base2 = createRun("base2", null, 24, 2);
        CompletedRun cand1 = createRun("cand1", null, 24, 2);

        ComparisonConfig config = new ComparisonConfig(
                ComparisonStrategy.BASELINE,
                ComparisonSet.ofSingle(calibration.comparisons.schema.RunReference.of("/path/base1")),
                ComparisonSet.ofSingle(calibration.comparisons.schema.RunReference.of("/path/cand1")),
                null,
                "/out");

        assertThrows(
                IllegalArgumentException.class,
                () -> ComparisonPairPlanner.plan(config, List.of(base1, base2), List.of(cand1)));

        assertThrows(
                IllegalArgumentException.class, () -> ComparisonPairPlanner.plan(config, List.of(base1), List.of()));
    }

    // ==========================================
    // KEYED Tests
    // ==========================================

    @Test
    void testKeyedSingleKeyMatchingByCandidateIndex() {
        CompletedRun dir0 = createRun("direct-0", 0, 24, 2);
        CompletedRun dir1 = createRun("direct-1", 1, 48, 2);
        CompletedRun dir2 = createRun("direct-2", 2, 96, 2);

        CompletedRun stg0 = createRun("staged-0", 0, 24, 2);
        CompletedRun stg1 = createRun("staged-1", 1, 48, 2);
        CompletedRun stg2 = createRun("staged-2", 2, 96, 2);

        ComparisonKeyConfig keyConfig = ComparisonKeyConfig.ofPath("/origin/candidateIndex");
        ComparisonConfig config = new ComparisonConfig(
                ComparisonStrategy.KEYED,
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/direct-0"),
                        calibration.comparisons.schema.RunReference.of("/path/direct-1"),
                        calibration.comparisons.schema.RunReference.of("/path/direct-2"))),
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/staged-0"),
                        calibration.comparisons.schema.RunReference.of("/path/staged-1"),
                        calibration.comparisons.schema.RunReference.of("/path/staged-2"))),
                keyConfig,
                "/out");

        ComparisonPairPlan plan =
                ComparisonPairPlanner.plan(config, List.of(dir0, dir1, dir2), List.of(stg0, stg1, stg2));

        assertEquals(ComparisonStrategy.KEYED, plan.strategy());
        assertEquals(3, plan.pairs().size());

        assertEquals("direct-0", plan.pairs().get(0).baseline().identity().trialId());
        assertEquals("staged-0", plan.pairs().get(0).candidate().identity().trialId());
        assertEquals("0", plan.pairs().get(0).key().format());

        assertEquals("direct-1", plan.pairs().get(1).baseline().identity().trialId());
        assertEquals("staged-1", plan.pairs().get(1).candidate().identity().trialId());
        assertEquals("1", plan.pairs().get(1).key().format());

        assertEquals("direct-2", plan.pairs().get(2).baseline().identity().trialId());
        assertEquals("staged-2", plan.pairs().get(2).candidate().identity().trialId());
        assertEquals("2", plan.pairs().get(2).key().format());
    }

    @Test
    void testKeyedDeterministicSortingIndependentOfInputOrder() {
        CompletedRun dir0 = createRun("direct-0", 0, 24, 2);
        CompletedRun dir10 = createRun("direct-10", 10, 48, 2);
        CompletedRun dir2 = createRun("direct-2", 2, 96, 2);

        CompletedRun stg0 = createRun("staged-0", 0, 24, 2);
        CompletedRun stg10 = createRun("staged-10", 10, 48, 2);
        CompletedRun stg2 = createRun("staged-2", 2, 96, 2);

        ComparisonKeyConfig keyConfig = ComparisonKeyConfig.ofPath("/origin/candidateIndex");
        ComparisonConfig config = new ComparisonConfig(
                ComparisonStrategy.KEYED,
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/direct-10"),
                        calibration.comparisons.schema.RunReference.of("/path/direct-0"),
                        calibration.comparisons.schema.RunReference.of("/path/direct-2"))),
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/staged-2"),
                        calibration.comparisons.schema.RunReference.of("/path/staged-10"),
                        calibration.comparisons.schema.RunReference.of("/path/staged-0"))),
                keyConfig,
                "/out");

        // Input order is shuffled: dir10, dir0, dir2 vs stg2, stg10, stg0
        ComparisonPairPlan plan =
                ComparisonPairPlanner.plan(config, List.of(dir10, dir0, dir2), List.of(stg2, stg10, stg0));

        assertEquals(3, plan.pairs().size());
        // Natural numeric ordering: 0 -> 2 -> 10
        assertEquals("0", plan.pairs().get(0).key().format());
        assertEquals("direct-0", plan.pairs().get(0).baseline().identity().trialId());
        assertEquals("staged-0", plan.pairs().get(0).candidate().identity().trialId());

        assertEquals("2", plan.pairs().get(1).key().format());
        assertEquals("direct-2", plan.pairs().get(1).baseline().identity().trialId());
        assertEquals("staged-2", plan.pairs().get(1).candidate().identity().trialId());

        assertEquals("10", plan.pairs().get(2).key().format());
        assertEquals("direct-10", plan.pairs().get(2).baseline().identity().trialId());
        assertEquals("staged-10", plan.pairs().get(2).candidate().identity().trialId());
    }

    @Test
    void testKeyedCompoundKeyMatching() {
        CompletedRun base1 = createRun("base-24-2", null, 24, 2);
        CompletedRun base2 = createRun("base-48-4", null, 48, 4);

        CompletedRun cand1 = createRun("cand-24-2", null, 24, 2);
        CompletedRun cand2 = createRun("cand-48-4", null, 48, 4);

        ComparisonKeyConfig keyConfig = ComparisonKeyConfig.ofPaths(
                List.of("/calibrationConfig/workUnits", "/calibrationConfig/parallelSources"));
        ComparisonConfig config = new ComparisonConfig(
                ComparisonStrategy.KEYED,
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/base-48-4"),
                        calibration.comparisons.schema.RunReference.of("/path/base-24-2"))),
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/cand-24-2"),
                        calibration.comparisons.schema.RunReference.of("/path/cand-48-4"))),
                keyConfig,
                "/out");

        ComparisonPairPlan plan = ComparisonPairPlanner.plan(config, List.of(base2, base1), List.of(cand1, cand2));

        assertEquals(2, plan.pairs().size());
        assertEquals("[24, 2]", plan.pairs().get(0).key().format());
        assertEquals("base-24-2", plan.pairs().get(0).baseline().identity().trialId());
        assertEquals("cand-24-2", plan.pairs().get(0).candidate().identity().trialId());

        assertEquals("[48, 4]", plan.pairs().get(1).key().format());
        assertEquals("base-48-4", plan.pairs().get(1).baseline().identity().trialId());
        assertEquals("cand-48-4", plan.pairs().get(1).candidate().identity().trialId());
    }

    @Test
    void testKeyedDuplicateBaselineKeyThrows() {
        CompletedRun dir0_a = createRun("direct-0a", 0, 24, 2);
        CompletedRun dir0_b = createRun("direct-0b", 0, 48, 4);
        CompletedRun stg0 = createRun("staged-0", 0, 24, 2);

        ComparisonKeyConfig keyConfig = ComparisonKeyConfig.ofPath("/origin/candidateIndex");
        ComparisonConfig config = new ComparisonConfig(
                ComparisonStrategy.KEYED,
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/direct-0a"),
                        calibration.comparisons.schema.RunReference.of("/path/direct-0b"))),
                ComparisonSet.ofSingle(calibration.comparisons.schema.RunReference.of("/path/staged-0")),
                keyConfig,
                "/out");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ComparisonPairPlanner.plan(config, List.of(dir0_a, dir0_b), List.of(stg0)));

        assertTrue(ex.getMessage().contains("Duplicate baseline comparison key '0'"));
    }

    @Test
    void testKeyedDuplicateCandidateKeyThrows() {
        CompletedRun dir0 = createRun("direct-0", 0, 24, 2);
        CompletedRun stg0_a = createRun("staged-0a", 0, 24, 2);
        CompletedRun stg0_b = createRun("staged-0b", 0, 48, 4);

        ComparisonKeyConfig keyConfig = ComparisonKeyConfig.ofPath("/origin/candidateIndex");
        ComparisonConfig config = new ComparisonConfig(
                ComparisonStrategy.KEYED,
                ComparisonSet.ofSingle(calibration.comparisons.schema.RunReference.of("/path/direct-0")),
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/staged-0a"),
                        calibration.comparisons.schema.RunReference.of("/path/staged-0b"))),
                keyConfig,
                "/out");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ComparisonPairPlanner.plan(config, List.of(dir0), List.of(stg0_a, stg0_b)));

        assertTrue(ex.getMessage().contains("Duplicate candidate comparison key '0'"));
    }

    @Test
    void testKeyedMissingMatchFailsWhenRequireCompleteMatchTrue() {
        CompletedRun dir0 = createRun("direct-0", 0, 24, 2);
        CompletedRun dir1 = createRun("direct-1", 1, 48, 2);
        CompletedRun stg0 = createRun("staged-0", 0, 24, 2);

        ComparisonKeyConfig keyConfig = new ComparisonKeyConfig(List.of("/origin/candidateIndex"), true);
        ComparisonConfig config = new ComparisonConfig(
                ComparisonStrategy.KEYED,
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/direct-0"),
                        calibration.comparisons.schema.RunReference.of("/path/direct-1"))),
                ComparisonSet.ofSingle(calibration.comparisons.schema.RunReference.of("/path/staged-0")),
                keyConfig,
                "/out");

        // Missing candidate match for baseline direct-1
        IllegalArgumentException ex1 = assertThrows(
                IllegalArgumentException.class,
                () -> ComparisonPairPlanner.plan(config, List.of(dir0, dir1), List.of(stg0)));
        assertTrue(ex1.getMessage().contains("Unmatched baseline comparison key '1'"));

        // Missing baseline match for candidate staged-1
        CompletedRun stg1 = createRun("staged-1", 1, 48, 2);
        IllegalArgumentException ex2 = assertThrows(
                IllegalArgumentException.class,
                () -> ComparisonPairPlanner.plan(config, List.of(dir0), List.of(stg0, stg1)));
        assertTrue(ex2.getMessage().contains("Unmatched candidate comparison key '1'"));
    }

    @Test
    void testKeyedMissingMatchReportedAndSkippedWhenRequireCompleteMatchFalse() {
        CompletedRun dir0 = createRun("direct-0", 0, 24, 2);
        CompletedRun dir1 = createRun("direct-1", 1, 48, 2); // Unmatched baseline
        CompletedRun stg0 = createRun("staged-0", 0, 24, 2);
        CompletedRun stg2 = createRun("staged-2", 2, 96, 2); // Unmatched candidate

        ComparisonKeyConfig keyConfig = new ComparisonKeyConfig(List.of("/origin/candidateIndex"), false);
        ComparisonConfig config = new ComparisonConfig(
                ComparisonStrategy.KEYED,
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/direct-0"),
                        calibration.comparisons.schema.RunReference.of("/path/direct-1"))),
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/staged-0"),
                        calibration.comparisons.schema.RunReference.of("/path/staged-2"))),
                keyConfig,
                "/out");

        ComparisonPairPlan plan = ComparisonPairPlanner.plan(config, List.of(dir0, dir1), List.of(stg0, stg2));

        // Exactly 1 matched pair
        assertEquals(1, plan.pairs().size());
        assertEquals("0", plan.pairs().getFirst().key().format());
        assertEquals("direct-0", plan.pairs().getFirst().baseline().identity().trialId());
        assertEquals("staged-0", plan.pairs().getFirst().candidate().identity().trialId());

        // Unmatched reported
        assertEquals(1, plan.unmatchedBaselineKeys().size());
        assertEquals("1", plan.unmatchedBaselineKeys().getFirst().format());

        assertEquals(1, plan.unmatchedCandidateKeys().size());
        assertEquals("2", plan.unmatchedCandidateKeys().getFirst().format());
    }

    // ==========================================
    // CROSS Tests
    // ==========================================

    @Test
    void testCrossModeProducesCartesianProduct() {
        CompletedRun dirA = createRun("direct-a", null, 24, 2);
        CompletedRun dirB = createRun("direct-b", null, 48, 4);

        CompletedRun stgX = createRun("staged-x", null, 24, 2);
        CompletedRun stgY = createRun("staged-y", null, 48, 4);
        CompletedRun stgZ = createRun("staged-z", null, 96, 8);

        ComparisonConfig config = new ComparisonConfig(
                ComparisonStrategy.CROSS,
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/direct-a"),
                        calibration.comparisons.schema.RunReference.of("/path/direct-b"))),
                ComparisonSet.ofRuns(List.of(
                        calibration.comparisons.schema.RunReference.of("/path/staged-x"),
                        calibration.comparisons.schema.RunReference.of("/path/staged-y"),
                        calibration.comparisons.schema.RunReference.of("/path/staged-z"))),
                null,
                "/out");

        ComparisonPairPlan plan = ComparisonPairPlanner.plan(config, List.of(dirA, dirB), List.of(stgX, stgY, stgZ));

        assertEquals(ComparisonStrategy.CROSS, plan.strategy());
        // 2 baselines * 3 candidates = 6 pairs
        assertEquals(6, plan.pairs().size());

        // Pair 0: dirA vs stgX
        assertEquals("direct-a", plan.pairs().get(0).baseline().identity().trialId());
        assertEquals("staged-x", plan.pairs().get(0).candidate().identity().trialId());

        // Pair 1: dirA vs stgY
        assertEquals("direct-a", plan.pairs().get(1).baseline().identity().trialId());
        assertEquals("staged-y", plan.pairs().get(1).candidate().identity().trialId());

        // Pair 2: dirA vs stgZ
        assertEquals("direct-a", plan.pairs().get(2).baseline().identity().trialId());
        assertEquals("staged-z", plan.pairs().get(2).candidate().identity().trialId());

        // Pair 3: dirB vs stgX
        assertEquals("direct-b", plan.pairs().get(3).baseline().identity().trialId());
        assertEquals("staged-x", plan.pairs().get(3).candidate().identity().trialId());

        // Pair 4: dirB vs stgY
        assertEquals("direct-b", plan.pairs().get(4).baseline().identity().trialId());
        assertEquals("staged-y", plan.pairs().get(4).candidate().identity().trialId());

        // Pair 5: dirB vs stgZ
        assertEquals("direct-b", plan.pairs().get(5).baseline().identity().trialId());
        assertEquals("staged-z", plan.pairs().get(5).candidate().identity().trialId());

        // No intra-set pairs
        for (ComparisonPair pair : plan.pairs()) {
            assertFalse(pair.baseline().identity().trialId().startsWith("staged"));
            assertFalse(pair.candidate().identity().trialId().startsWith("direct"));
        }
    }
}
