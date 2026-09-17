package calibration.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.euhedral_execution.core.config.IdlePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdlePolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode input() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"cpuSet":[2,4],"parallelSources":2,"totalRequiredExecutions":100,
             "invocationTimeoutMillis":1000}
            """);
    }

    @Test
    void localFunctionSurvivesJsonAndAllConfigCopies() throws Exception {
        ObjectNode json = input().put("cacheScarcityGateEnabled", true);
        json.set("cacheTimingFunction", mapper.readTree("""
            {"normalizationVersion":"bounded-v1","means":[0.5,2,8],"scales":[0.5,2,8],
             "supportMin":[0,0,0],"supportMax":[1,4,16],
             "parkCoefficients":[0,0,0,0],"halfLifeCoefficients":[0,0,0,0],
             "parkReferenceNanos":15000,"halfLifeReferenceNanos":1000000,
             "parkMinNanos":0,"parkMaxNanos":814375,"halfLifeMinNanos":250000,"halfLifeMaxNanos":2000000}
            """));
        var config = mapper.treeToValue(json, CalibrationBenchmarkConfig.class);
        assertNotNull(config.toCacheTimingConfig().function());
        assertEquals(config, mapper.readValue(mapper.writeValueAsString(config), CalibrationBenchmarkConfig.class));
        for (var copy : List.of(
                config.withLifecycleMode(CalibrationLifecycleMode.CONTINUOUS),
                config.withCurrentCacheActuatorIdentity())) {
            assertEquals(
                    config.cacheTimingFunction(), copy.toCacheTimingConfig().function());
        }
    }

    @Test
    void importedProfilesResolveAndPersistBothValues(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        ObjectNode library = mapper.createObjectNode();
        library.putObject("calibrationProfiles")
                .set("timed", input().put("idleParkNs", 43000L).put("contentionHalfLifeNanos", 7000000L));
        mapper.writeValue(dir.resolve("library.json").toFile(), library);
        java.nio.file.Files.writeString(dir.resolve("harness.json"), """
            {"imports":[{"path":"library.json","namespace":"local"}],
             "trials":[{"id":"timed","forks":1,"warmups":1,"iterations":1,"calibrationProfile":"local.timed"}]}
            """);
        HarnessConfig harness = ProfileLibraryLoader.loadAndResolve(
                        dir.resolve("harness.json").toFile(), mapper)
                .resolveCalibrationProfiles();
        TrialConfig resolved = harness.trials().getFirst();
        assertEquals(
                new IdlePolicy(43000L, 7000000L, null),
                resolved.calibrationConfig().toCacheTimingConfig());
        ObjectNode persisted = mapper.valueToTree(resolved);
        assertEquals(43000L, persisted.at("/calibrationConfig/idleParkNs").longValue());
        assertEquals(
                7000000L,
                persisted.at("/calibrationConfig/contentionHalfLifeNanos").longValue());
        assertEquals(resolved, mapper.treeToValue(persisted, TrialConfig.class));
    }

    @Test
    void omittedHistoricalValuesAndRoundTrip() throws Exception {
        CalibrationBenchmarkConfig defaults = mapper.treeToValue(input(), CalibrationBenchmarkConfig.class);
        assertEquals(new IdlePolicy(15000L, 1000000L, null), defaults.toCacheTimingConfig());
        ObjectNode json = mapper.valueToTree(defaults);
        assertEquals(1000000L, json.get("contentionHalfLifeNanos").longValue());
        assertFalse(json.has("toCacheTimingConfig"));
        assertFalse(json.has("idlePolicy"));
        json.put("idleParkNs", 43000L).put("contentionHalfLifeNanos", 7000000L);
        CalibrationBenchmarkConfig custom = mapper.treeToValue(json, CalibrationBenchmarkConfig.class);
        assertEquals(custom, mapper.readValue(mapper.writeValueAsString(custom), CalibrationBenchmarkConfig.class));
        for (CalibrationBenchmarkConfig copy : List.of(
                custom.withLifecycleMode(CalibrationLifecycleMode.CONTINUOUS),
                custom.withCurrentCacheActuatorIdentity())) {
            assertEquals(new IdlePolicy(43000L, 7000000L, null), copy.toCacheTimingConfig());
        }
    }

    @Test
    void rejectsInvalidTimingJson() throws Exception {
        ObjectNode json = input().put("idleParkNs", -1L);
        assertThrows(Exception.class, () -> mapper.treeToValue(json, CalibrationBenchmarkConfig.class));
        json.put("idleParkNs", 0L).put("contentionHalfLifeNanos", 0L);
        assertThrows(Exception.class, () -> mapper.treeToValue(json, CalibrationBenchmarkConfig.class));
        json.put("contentionHalfLifeNanos", -1L);
        assertThrows(Exception.class, () -> mapper.treeToValue(json, CalibrationBenchmarkConfig.class));
    }

    @Test
    void legacyHarnessAliasResolvesBeforeCanonicalConfigAndRejectsConflict() throws Exception {
        ObjectMapper harness = LegacyCacheTimingInput.harnessMapper(mapper, "43000");
        ObjectNode json = input();
        assertEquals(
                43000L,
                harness.treeToValue(json, CalibrationBenchmarkConfig.class).cacheParkNs());
        assertEquals(
                15000L,
                mapper.treeToValue(json, CalibrationBenchmarkConfig.class).cacheParkNs());
        json.put("idleParkNs", 43000L);
        assertEquals(
                43000L,
                harness.treeToValue(json, CalibrationBenchmarkConfig.class).cacheParkNs());
        json.put("idleParkNs", 15000L);
        assertThrows(Exception.class, () -> harness.treeToValue(json, CalibrationBenchmarkConfig.class));
        assertThrows(IllegalArgumentException.class, () -> LegacyCacheTimingInput.harnessMapper(mapper, "-1"));
        assertThrows(IllegalArgumentException.class, () -> LegacyCacheTimingInput.harnessMapper(mapper, "oops"));
        TrialConfig trial = new TrialConfig(
                1,
                1,
                1,
                List.of("-Deuhedral.fragment.cache.parkNs=1"),
                mapper.treeToValue(json, CalibrationBenchmarkConfig.class));
        assertThrows(IllegalArgumentException.class, () -> LegacyCacheTimingInput.validateTrialArguments(trial));
    }
}
