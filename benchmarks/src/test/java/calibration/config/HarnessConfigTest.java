package calibration.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Unit tests for HarnessConfig JSON parsing, metadata validation, and round-tripping.
class HarnessConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static HarnessConfig.TrialConfig dummyTrialConfig() {
        return new HarnessConfig.TrialConfig(1, 1, 1, null, dummyCalibrationConfig());
    }

    private static HarnessConfig.TrialConfig dummyTrialConfigWithId(String id) {
        return new HarnessConfig.TrialConfig(
                id, "name", "group", null, null, null, null, true, 1, 1, 1, null, dummyCalibrationConfig());
    }

    private static HarnessConfig.TrialConfig dummyTrialConfigWithMeta(
            String id, String name, String group, String baselineTrialId) {
        return new HarnessConfig.TrialConfig(
                id,
                name,
                group,
                "desc",
                "hypothesis",
                baselineTrialId,
                null,
                true,
                1,
                1,
                1,
                null,
                dummyCalibrationConfig());
    }

    private static CalibrationBenchmarkConfig dummyCalibrationConfig() {
        return new CalibrationBenchmarkConfig(
                1, 1, 10, false, 100, 1000, FragmentDecisionWeights.DEFAULT, false, false, false, false, false, false);
    }

    /// Verifies static constant CURRENT_SCHEMA_VERSION.
    @Test
    void currentSchemaVersionIsOne() {
        assertEquals(1, HarnessConfig.CURRENT_SCHEMA_VERSION);
    }

    /// Verifies deserialization of all new metadata properties and JSON round-trip equivalence.
    @Test
    void parseAllNewMetadataAndRoundTrip() throws Exception {
        String json = """
            {
              "schemaVersion": 1,
              "id": "exp-001",
              "name": "High Throughput Experiment",
              "description": "Tests execution under high contention",
              "labels": {
                "env": "staging",
                "team": "core"
              },
              "trials": [
                {
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "parallelSources": 4,
                    "orderedSources": 2,
                    "workUnits": 100,
                    "randomizeWork": true,
                    "totalRequiredExecutions": 1000000,
                    "invocationTimeoutMillis": 60000,
                    "decisionWeights": {
                      "idleContentionThresholds": { "xsContention": 1, "sContention": 1, "mContention": 1, "hContention": 1 },
                      "idleBodyCostWeights": [],
                      "idleTimeNs": [],
                      "execContentionThresholds": { "xsContention": 1, "sContention": 1, "mContention": 1, "hContention": 1 },
                      "execBodyCostWeights": [],
                      "executionPolicies": []
                    },
                    "observeCycleStart": false,
                    "observeBatchProgress": false,
                    "observeBatchComplete": false,
                    "observeRawBodyCost": false,
                    "observeIdleDecision": false,
                    "observeExecDecision": false
                  }
                }
              ]
            }
            """;

        HarnessConfig config = mapper.readValue(json, HarnessConfig.class);

        assertEquals(Integer.valueOf(1), config.schemaVersion());
        assertEquals("exp-001", config.id());
        assertEquals("High Throughput Experiment", config.name());
        assertEquals("Tests execution under high contention", config.description());
        assertEquals(Map.of("env", "staging", "team", "core"), config.labels());
        assertEquals(1, config.trials().size());

        // Test round-trip serialization / deserialization
        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTripConfig = mapper.readValue(reSerialized, HarnessConfig.class);

        assertEquals(config, roundTripConfig);
    }

    /// Verifies non-positive schemaVersion is rejected.
    @Test
    void rejectInvalidSchemaVersion() {
        String jsonZero = """
            {
              "schemaVersion": 0,
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonZero, HarnessConfig.class));

        String jsonNegative = """
            {
              "schemaVersion": -1,
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonNegative, HarnessConfig.class));

        // Also verify via constructor
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(0, "id", "name", null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(-5, "id", "name", null, null, List.of()));
    }

    /// Verifies blank id or blank name is rejected.
    @Test
    void rejectBlankIdAndName() {
        String jsonBlankId = """
            {
              "id": "   ",
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonBlankId, HarnessConfig.class));

        String jsonEmptyId = """
            {
              "id": "",
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonEmptyId, HarnessConfig.class));

        String jsonBlankName = """
            {
              "name": "   ",
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonBlankName, HarnessConfig.class));

        String jsonEmptyName = """
            {
              "name": "",
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonEmptyName, HarnessConfig.class));

        // Also verify via constructor directly
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(1, "", "name", null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(1, "   ", "name", null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(1, "id", "", null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(1, "id", "   ", null, null, List.of()));
    }

    /// Verifies defensive copying of labels.
    @Test
    void defensivelyCopyLabels() {
        Map<String, String> originalLabels = Map.of("key", "val");
        HarnessConfig config = new HarnessConfig(1, "id", "name", "desc", originalLabels, List.of(dummyTrialConfig()));

        assertNotNull(config.labels());
        assertThrows(UnsupportedOperationException.class, () -> config.labels().put("newKey", "newVal"));
    }

    /// Verifies null or empty trials are rejected.
    @Test
    void rejectNullOrEmptyTrials() {
        assertThrows(NullPointerException.class, () -> new HarnessConfig(1, "id", "name", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(1, "id", "name", null, null, List.of()));
    }

    /// Verifies trial metadata deserialization and round-trip equivalence.
    @Test
    void parseTrialMetadataAndRoundTrip() throws Exception {
        String json = """
            {
              "trials": [
                {
                  "id": "trial-1",
                  "name": "Trial One",
                  "group": "group-a",
                  "description": "First trial",
                  "hypothesis": "Hypothesis A",
                  "baselineTrialId": "baseline-0",
                  "tags": ["tag1", "tag2"],
                  "enabled": true,
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "parallelSources": 4,
                    "orderedSources": 2,
                    "workUnits": 100,
                    "randomizeWork": true,
                    "totalRequiredExecutions": 1000000,
                    "invocationTimeoutMillis": 60000,
                    "decisionWeights": {
                      "idleContentionThresholds": { "xsContention": 1, "sContention": 1, "mContention": 1, "hContention": 1 },
                      "idleBodyCostWeights": [],
                      "idleTimeNs": [],
                      "execContentionThresholds": { "xsContention": 1, "sContention": 1, "mContention": 1, "hContention": 1 },
                      "execBodyCostWeights": [],
                      "executionPolicies": []
                    },
                    "observeCycleStart": false,
                    "observeBatchProgress": false,
                    "observeBatchComplete": false,
                    "observeRawBodyCost": false,
                    "observeIdleDecision": false,
                    "observeExecDecision": false
                  }
                }
              ]
            }
            """;

        HarnessConfig config = mapper.readValue(json, HarnessConfig.class);
        assertEquals(1, config.trials().size());
        HarnessConfig.TrialConfig trial = config.trials().get(0);

        assertEquals("trial-1", trial.id());
        assertEquals("Trial One", trial.name());
        assertEquals("group-a", trial.group());
        assertEquals("First trial", trial.description());
        assertEquals("Hypothesis A", trial.hypothesis());
        assertEquals("baseline-0", trial.baselineTrialId());
        assertEquals(List.of("tag1", "tag2"), trial.tags());
        assertEquals(Boolean.TRUE, trial.enabled());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }

    /// Verifies blank trial id, name, group, or baselineTrialId are rejected.
    @Test
    void rejectBlankTrialMetadataFields() {
        assertThrows(IllegalArgumentException.class, () -> dummyTrialConfigWithMeta("  ", "name", "group", "baseline"));
        assertThrows(IllegalArgumentException.class, () -> dummyTrialConfigWithMeta("id", "  ", "group", "baseline"));
        assertThrows(IllegalArgumentException.class, () -> dummyTrialConfigWithMeta("id", "name", "  ", "baseline"));
        assertThrows(IllegalArgumentException.class, () -> dummyTrialConfigWithMeta("id", "name", "group", "  "));
    }

    /// Verifies tags are defensively copied and blank or null tags are rejected.
    @Test
    void defensivelyCopyAndValidateTags() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig.TrialConfig(
                        "id",
                        "name",
                        "group",
                        null,
                        null,
                        null,
                        List.of("tag1", "   "),
                        true,
                        1,
                        1,
                        5,
                        null,
                        dummyCalibrationConfig()));

        List<String> mutableTags = Arrays.asList("tag1", "tag2");
        HarnessConfig.TrialConfig trial = new HarnessConfig.TrialConfig(
                "id", "name", "group", null, null, null, mutableTags, true, 1, 1, 5, null, dummyCalibrationConfig());

        assertNotNull(trial.tags());
        assertThrows(UnsupportedOperationException.class, () -> trial.tags().add("tag3"));
    }

    /// Verifies duplicate trial IDs are rejected at the HarnessConfig level.
    @Test
    void rejectDuplicateTrialIds() {
        HarnessConfig.TrialConfig trial1 = dummyTrialConfigWithId("trial-1");
        HarnessConfig.TrialConfig trial2 = dummyTrialConfigWithId("trial-1");

        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(List.of(trial1, trial2)));
    }

    /// Verifies multiple trials with unique or null IDs are accepted.
    @Test
    void allowMultipleTrialsWithUniqueOrNullIds() {
        HarnessConfig.TrialConfig trial1 = dummyTrialConfigWithId("trial-1");
        HarnessConfig.TrialConfig trial2 = dummyTrialConfigWithId("trial-2");
        HarnessConfig.TrialConfig trial3 = dummyTrialConfigWithId(null);
        HarnessConfig.TrialConfig trial4 = dummyTrialConfigWithId(null);

        HarnessConfig config = new HarnessConfig(List.of(trial1, trial2, trial3, trial4));
        assertEquals(4, config.trials().size());
    }

    /// Verifies preset JSON file example_harness_config.json parses correctly.
    @Test
    void parseExampleHarnessConfigPreset() throws Exception {
        File file = new File("src/main/java/calibration/presets/example_harness_config.json");
        if (!file.exists()) {
            file = new File("benchmarks/src/main/java/calibration/presets/example_harness_config.json");
        }
        assertTrue(file.exists(), "example_harness_config.json should exist");
        HarnessConfig config = mapper.readValue(file, HarnessConfig.class);
        assertEquals(1, config.schemaVersion());
        assertEquals("example-harness-config", config.id());
        assertEquals("Example Calibration Harness Configuration", config.name());
        assertNotNull(config.trials());
        assertEquals(1, config.trials().size());
        assertEquals("trial-001", config.trials().get(0).id());
    }

    /// Verifies preset JSON file exec_contention_band_calibration.json parses correctly.
    @Test
    void parseExecContentionBandCalibrationPreset() throws Exception {
        File file = new File("src/main/java/calibration/presets/exec_contention_band_calibration.json");
        if (!file.exists()) {
            file = new File("benchmarks/src/main/java/calibration/presets/exec_contention_band_calibration.json");
        }
        assertTrue(file.exists(), "exec_contention_band_calibration.json should exist");
        HarnessConfig config = mapper.readValue(file, HarnessConfig.class);
        assertEquals(1, config.schemaVersion());
        assertEquals("exec-contention-band-calibration", config.id());
        assertEquals("Execution Contention Band Calibration", config.name());
        assertNotNull(config.trials());
    }
}
