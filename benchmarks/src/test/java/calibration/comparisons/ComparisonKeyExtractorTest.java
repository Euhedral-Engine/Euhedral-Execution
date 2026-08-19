package calibration.comparisons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.RunArtifacts;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ThroughputResult;
import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.ComparisonKeyConfig;
import calibration.config.OriginType;
import calibration.config.TrialConfig;
import calibration.config.TrialOrigin;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComparisonKeyExtractorTest {

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

    @Test
    void testExtractSingleKeyByCandidateIndex() {
        CompletedRun run0 = createRun("trial-0", 0, 24, 2);
        CompletedRun run1 = createRun("trial-1", 1, 48, 4);

        ComparisonKeyConfig keyConfig = ComparisonKeyConfig.ofPath("/origin/candidateIndex");

        ComparisonKey key0 = ComparisonKeyExtractor.extract(run0, keyConfig);
        ComparisonKey key1 = ComparisonKeyExtractor.extract(run1, keyConfig);

        assertEquals("0", key0.format());
        assertEquals("1", key1.format());
        assertTrue(key0.compareTo(key1) < 0);
        assertEquals(key0, ComparisonKey.of(ComparisonKeyValue.of(0)));
    }

    @Test
    void testExtractCompoundKey() {
        CompletedRun run = createRun("trial-compound", 0, 48, 4);
        ComparisonKeyConfig keyConfig = ComparisonKeyConfig.ofPaths(
                List.of("/calibrationConfig/workUnits", "/calibrationConfig/parallelSources"));

        ComparisonKey key = ComparisonKeyExtractor.extract(run, keyConfig);

        assertEquals("[48, 4]", key.format());
        assertEquals(2, key.values().size());
        assertEquals(ComparisonKeyValue.of(48), key.values().get(0));
        assertEquals(ComparisonKeyValue.of(4), key.values().get(1));
    }

    @Test
    void testNumericNaturalOrderingVersusLexical() {
        ComparisonKeyValue v2 = ComparisonKeyValue.of(2);
        ComparisonKeyValue v10 = ComparisonKeyValue.of(10);
        ComparisonKeyValue v24 = ComparisonKeyValue.of(24);
        ComparisonKeyValue v100 = ComparisonKeyValue.of(100);

        assertTrue(v2.compareTo(v10) < 0, "2 must be less than 10 numerically");
        assertTrue(v10.compareTo(v24) < 0, "10 must be less than 24 numerically");
        assertTrue(v24.compareTo(v100) < 0, "24 must be less than 100 numerically");

        ComparisonKey k2 = ComparisonKey.of(v2);
        ComparisonKey k10 = ComparisonKey.of(v10);
        assertTrue(k2.compareTo(k10) < 0);
    }

    @Test
    void testStringAndBooleanKeyExtraction() {
        CompletedRun run = createRun("trial-str", 0, 24, 2);
        ComparisonKeyConfig strKey = ComparisonKeyConfig.ofPath("/name");
        ComparisonKeyConfig boolKey = ComparisonKeyConfig.ofPath("/enabled");

        ComparisonKey kStr = ComparisonKeyExtractor.extract(run, strKey);
        ComparisonKey kBool = ComparisonKeyExtractor.extract(run, boolKey);

        assertEquals("Name trial-str", kStr.format());
        assertEquals("true", kBool.format());
    }

    @Test
    void testMissingJsonPointerPathThrows() {
        CompletedRun run = createRun("trial-0", 0, 24, 2);
        ComparisonKeyConfig keyConfig = ComparisonKeyConfig.ofPath("/origin/nonexistentField");

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> ComparisonKeyExtractor.extract(run, keyConfig));

        assertTrue(ex.getMessage().contains("/origin/nonexistentField"));
        assertTrue(ex.getMessage().contains("trial-0"));
    }

    @Test
    void testInvalidJsonPointerSyntaxThrows() {
        CompletedRun run = createRun("trial-1", 0, 24, 2);
        ComparisonKeyConfig invalidKey = new ComparisonKeyConfig(List.of("invalid-pointer-no-slash"), true);

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> ComparisonKeyExtractor.extract(run, invalidKey));

        assertTrue(ex.getMessage().contains("invalid-pointer-no-slash"));
    }

    @Test
    void testObjectOrArrayNodeAsKeyThrows() {
        CompletedRun run = createRun("trial-1", 0, 24, 2);
        ComparisonKeyConfig objKey = ComparisonKeyConfig.ofPath("/calibrationConfig");

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> ComparisonKeyExtractor.extract(run, objKey));

        assertTrue(ex.getMessage().contains("cannot be represented as a scalar key value"));
    }

    @Test
    void testKeyUsesResolvedTrialConfigNotTrialId() {
        // Run trialId is "arbitrary-id-999" but candidateIndex in TrialConfig is 5
        CompletedRun run = createRun("arbitrary-id-999", 5, 24, 2);
        ComparisonKeyConfig keyConfig = ComparisonKeyConfig.ofPath("/origin/candidateIndex");

        ComparisonKey key = ComparisonKeyExtractor.extract(run, keyConfig);
        assertEquals("5", key.format());
    }
}
