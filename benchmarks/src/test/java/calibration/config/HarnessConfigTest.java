package calibration.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Unit tests for HarnessConfig JSON parsing, metadata validation, and round-tripping.
class HarnessConfigTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(JsonParser.Feature.ALLOW_COMMENTS, true);

    private static TrialConfig dummyTrialConfig() {
        return new TrialConfig(1, 1, 1, null, dummyCalibrationConfig());
    }

    private static TrialConfig dummyTrialConfigWithId(String id) {
        return new TrialConfig(
                id, "name", "group", null, null, null, null, true, 1, 1, 1, null, dummyCalibrationConfig());
    }

    private static TrialConfig dummyTrialConfigWithComparison(String id, ComparisonConfig comparison) {
        return new TrialConfig(
                id,
                "name",
                "group",
                "desc",
                "hypothesis",
                comparison,
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
                List.of(1, 2, 3, 4),
                4,
                2,
                100,
                false,
                100,
                1000,
                FragmentDecisionWeights.DEFAULT,
                1024,
                false,
                false,
                false,
                false,
                false,
                false);
    }

    /// Verifies static constant CURRENT_SCHEMA_VERSION.
    @Test
    void currentSchemaVersionIsOne() {
        assertEquals(1, HarnessConfig.CURRENT_SCHEMA_VERSION);
    }

    /// Verifies old minimal JSON with only trials parses cleanly.
    @Test
    void parseOldMinimalJsonWithOnlyTrials() throws Exception {
        String json = """
            {
              "trials": [
                {
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
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
        assertNull(config.schemaVersion());
        assertNull(config.id());
        assertNull(config.name());
        assertNull(config.description());
        assertNull(config.labels());
        assertNull(config.runOptions());
        assertNull(config.artifacts());
        assertNull(config.calibrationProfiles());
        assertNull(config.decisionWeightProfiles());
        assertNull(config.sweeps());
        assertNull(config.searches());
        assertEquals(1, config.trials().size());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
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
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
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

    /// Verifies blank id, name, or description is rejected.
    @Test
    void rejectBlankIdNameAndDescription() {
        String jsonBlankId = """
            {
              "id": "   ",
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonBlankId, HarnessConfig.class));

        String jsonBlankDescription = """
            {
              "description": "   ",
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonBlankDescription, HarnessConfig.class));

        // Also verify via constructor directly
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(1, "", "name", null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(1, "   ", "name", null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(1, "id", "", null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(1, "id", "   ", null, null, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        1, "id", "name", "   ", null, null, null, null, null, null, null, List.of(dummyTrialConfig())));
    }

    /// Verifies defensive copying of labels and rejection of blank label keys/values.
    @Test
    void defensivelyCopyAndValidateLabels() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        1,
                        "id",
                        "name",
                        "desc",
                        Map.of("  ", "val"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(dummyTrialConfig())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        1,
                        "id",
                        "name",
                        "desc",
                        Map.of("key", "   "),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(dummyTrialConfig())));

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

    /// Verifies HarnessRunOptions parsing and round-trip.
    @Test
    void parseRunOptionsAndRoundTrip() throws Exception {
        String json = """
            {
              "runOptions": {
                "randomizeTrialOrder": true,
                "randomSeed": 42,
                "failFast": false,
                "repeatCount": 3
              },
              "trials": [
                {
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
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
        assertNotNull(config.runOptions());
        assertEquals(Boolean.TRUE, config.runOptions().randomizeTrialOrder());
        assertEquals(Long.valueOf(42L), config.runOptions().randomSeed());
        assertEquals(Boolean.FALSE, config.runOptions().failFast());
        assertEquals(Integer.valueOf(3), config.runOptions().repeatCount());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }

    /// Verifies HarnessRunOptions validation for repeatCount.
    @Test
    void rejectInvalidRepeatCount() {
        String jsonZeroRepeat = """
            {
              "runOptions": {
                "repeatCount": 0
              },
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonZeroRepeat, HarnessConfig.class));

        String jsonNegativeRepeat = """
            {
              "runOptions": {
                "repeatCount": -2
              },
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonNegativeRepeat, HarnessConfig.class));

        assertThrows(IllegalArgumentException.class, () -> new HarnessRunOptions(null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new HarnessRunOptions(null, null, null, -1));
    }

    /// Verifies HarnessRunOptions accepts any long for randomSeed and null booleans.
    @Test
    void allowAnyRandomSeedAndNullBooleans() {
        HarnessRunOptions options = new HarnessRunOptions(null, -999L, null, 1);
        assertNull(options.randomizeTrialOrder());
        assertEquals(-999L, options.randomSeed());
        assertNull(options.failFast());
        assertEquals(1, options.repeatCount());
    }

    /// Verifies ArtifactConfig JSON parsing and round-trip.
    @Test
    void parseArtifactConfigAndRoundTrip() throws Exception {
        String json = """
            {
              "artifacts": {
                "outputDirectory": "build/reports/benchmarks",
                "retainExpandedConfig": true,
                "retainRawBenchmarkOutput": false,
                "retainObserverData": true,
                "retainPerForkResults": false,
                "retainPerIterationResults": true
              },
              "trials": [
                {
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
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
        assertNotNull(config.artifacts());
        assertEquals("build/reports/benchmarks", config.artifacts().outputDirectory());
        assertEquals(Boolean.TRUE, config.artifacts().retainExpandedConfig());
        assertEquals(Boolean.FALSE, config.artifacts().retainRawBenchmarkOutput());
        assertEquals(Boolean.TRUE, config.artifacts().retainObserverData());
        assertEquals(Boolean.FALSE, config.artifacts().retainPerForkResults());
        assertEquals(Boolean.TRUE, config.artifacts().retainPerIterationResults());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }

    /// Verifies ArtifactConfig validation for blank outputDirectory.
    @Test
    void rejectBlankOutputDirectory() {
        String jsonBlankDir = """
            {
              "artifacts": {
                "outputDirectory": "   "
              },
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonBlankDir, HarnessConfig.class));

        String jsonEmptyDir = """
            {
              "artifacts": {
                "outputDirectory": ""
              },
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonEmptyDir, HarnessConfig.class));

        assertThrows(IllegalArgumentException.class, () -> new ArtifactConfig("   ", true, true, true, true, true));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactConfig("", true, true, true, true, true));
    }

    /// Verifies ArtifactConfig allows null properties.
    @Test
    void allowNullArtifactFields() {
        ArtifactConfig artifacts = new ArtifactConfig(null, null, null, null, null, null);
        assertNull(artifacts.outputDirectory());
        assertNull(artifacts.retainExpandedConfig());
        assertNull(artifacts.retainRawBenchmarkOutput());
        assertNull(artifacts.retainObserverData());
        assertNull(artifacts.retainPerForkResults());
        assertNull(artifacts.retainPerIterationResults());
    }

    /// Verifies calibrationProfiles JSON parsing and round-trip equivalence.
    @Test
    void parseCalibrationProfilesAndRoundTrip() throws Exception {
        String json = """
            {
              "calibrationProfiles": {
                "profile-a": {
                  "cpuSet": [1, 2, 3, 4],
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
                  "rawSampleLimit": 1024,
                  "observeCycleStart": false,
                  "observeBatchProgress": false,
                  "observeBatchComplete": false,
                  "observeRawBodyCost": false,
                  "observeIdleDecision": false,
                  "observeExecDecision": false
                }
              },
              "trials": [
                {
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
                    "rawSampleLimit": 1024,
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
        assertNotNull(config.calibrationProfiles());
        assertEquals(1, config.calibrationProfiles().size());
        assertTrue(config.calibrationProfiles().containsKey("profile-a"));
        assertEquals(4, config.calibrationProfiles().get("profile-a").parallelSources());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }

    /// Verifies blank calibrationProfile names are rejected.
    @Test
    void rejectBlankCalibrationProfileName() {
        String jsonBlankProfileName = """
            {
              "calibrationProfiles": {
                "   ": {
                  "cpuSet": [1, 2, 3, 4],
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
                  "rawSampleLimit": 1024,
                  "observeCycleStart": false,
                  "observeBatchProgress": false,
                  "observeBatchComplete": false,
                  "observeRawBodyCost": false,
                  "observeIdleDecision": false,
                  "observeExecDecision": false
                }
              },
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonBlankProfileName, HarnessConfig.class));

        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("  ", dummyCalibrationConfig()),
                        List.of(dummyTrialConfig())));
    }

    /// Verifies null calibrationProfile values are rejected and profiles map is defensively copied.
    @Test
    void rejectNullCalibrationProfileValueAndDefensivelyCopy() {
        Map<String, CalibrationBenchmarkConfig> mutableProfiles = new HashMap<>();
        mutableProfiles.put("profile-1", dummyCalibrationConfig());
        mutableProfiles.put("profile-2", null);

        assertThrows(
                NullPointerException.class,
                () -> new HarnessConfig(
                        null, null, null, null, null, null, null, mutableProfiles, List.of(dummyTrialConfig())));

        Map<String, CalibrationBenchmarkConfig> validProfiles = Map.of("profile-1", dummyCalibrationConfig());
        HarnessConfig config =
                new HarnessConfig(null, null, null, null, null, null, null, validProfiles, List.of(dummyTrialConfig()));

        assertNotNull(config.calibrationProfiles());
        assertThrows(
                UnsupportedOperationException.class,
                () -> config.calibrationProfiles().put("profile-2", dummyCalibrationConfig()));
    }

    /// Verifies decisionWeightProfiles JSON parsing and round-trip equivalence with distinct profiles.
    @Test
    void parseDecisionWeightProfilesAndRoundTrip() throws Exception {
        String json = """
            {
              "decisionWeightProfiles": {
                "default-weights": {
                  "idleContentionThresholds": { "xsContention": 1, "sContention": 1, "mContention": 1, "hContention": 1 },
                  "idleBodyCostWeights": [],
                  "idleTimeNs": [],
                  "execContentionThresholds": { "xsContention": 1, "sContention": 1, "mContention": 1, "hContention": 1 },
                  "execBodyCostWeights": [],
                  "executionPolicies": []
                },
                "aggressive-weights": {
                  "idleContentionThresholds": { "xsContention": 10, "sContention": 20, "mContention": 30, "hContention": 40 },
                  "idleBodyCostWeights": [],
                  "idleTimeNs": [],
                  "execContentionThresholds": { "xsContention": 10, "sContention": 20, "mContention": 30, "hContention": 40 },
                  "execBodyCostWeights": [],
                  "executionPolicies": []
                }
              },
              "trials": [
                {
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
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
        assertNotNull(config.decisionWeightProfiles());
        assertEquals(2, config.decisionWeightProfiles().size());
        assertTrue(config.decisionWeightProfiles().containsKey("default-weights"));
        assertTrue(config.decisionWeightProfiles().containsKey("aggressive-weights"));

        assertEquals(
                1,
                config.decisionWeightProfiles()
                        .get("default-weights")
                        .idleContentionThresholds()
                        .hContention());
        assertEquals(
                40,
                config.decisionWeightProfiles()
                        .get("aggressive-weights")
                        .idleContentionThresholds()
                        .hContention());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }

    /// Verifies blank decisionWeightProfiles names are rejected.
    @Test
    void rejectBlankDecisionWeightProfileName() {
        String jsonBlankProfileName = """
            {
              "decisionWeightProfiles": {
                "   ": {
                  "idleContentionThresholds": { "xsContention": 1, "sContention": 1, "mContention": 1, "hContention": 1 },
                  "idleBodyCostWeights": [],
                  "idleTimeNs": [],
                  "execContentionThresholds": { "xsContention": 1, "sContention": 1, "mContention": 1, "hContention": 1 },
                  "execBodyCostWeights": [],
                  "executionPolicies": []
                }
              },
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonBlankProfileName, HarnessConfig.class));

        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("  ", FragmentDecisionWeights.DEFAULT),
                        List.of(dummyTrialConfig())));
    }

    /// Verifies null decisionWeightProfiles values are rejected and map is defensively copied.
    @Test
    void rejectNullDecisionWeightProfileValueAndDefensivelyCopy() {
        Map<String, FragmentDecisionWeights> mutableProfiles = new HashMap<>();
        mutableProfiles.put("profile-1", FragmentDecisionWeights.DEFAULT);
        mutableProfiles.put("profile-2", null);

        assertThrows(
                NullPointerException.class,
                () -> new HarnessConfig(
                        null, null, null, null, null, null, null, null, mutableProfiles, List.of(dummyTrialConfig())));

        Map<String, FragmentDecisionWeights> validProfiles = Map.of("profile-1", FragmentDecisionWeights.DEFAULT);
        HarnessConfig config = new HarnessConfig(
                null, null, null, null, null, null, null, null, validProfiles, List.of(dummyTrialConfig()));

        assertNotNull(config.decisionWeightProfiles());
        assertThrows(
                UnsupportedOperationException.class,
                () -> config.decisionWeightProfiles().put("profile-2", FragmentDecisionWeights.DEFAULT));
    }

    /// Verifies sweeps JSON parsing and round-trip with various JsonNode value types.
    @Test
    void parseSweepsAndRoundTrip() throws Exception {
        String json = """
            {
              "sweeps": [
                {
                  "id": "sweep-001",
                  "baseTrialId": "trial-1",
                  "description": "Comprehensive parameter sweep",
                  "parameters": [
                    {
                      "path": "/calibrationConfig/parallelSources",
                      "values": [2, 4, 8]
                    },
                    {
                      "path": "/calibrationConfig/totalRequiredExecutions",
                      "values": [1000, 5000000000]
                    },
                    {
                      "path": "/calibrationConfig/observeCycleStart",
                      "values": [true, false]
                    },
                    {
                      "path": "/calibrationConfig/ratio",
                      "values": [0.25, 0.75]
                    },
                    {
                      "path": "/calibrationConfig/mode",
                      "values": ["FAST", "SLOW"]
                    },
                    {
                      "path": "/calibrationConfig/tags",
                      "values": [["tagA", "tagB"], ["tagC"]]
                    },
                    {
                      "path": "/calibrationConfig/weights",
                      "values": [{ "threshold": 10 }, { "threshold": 20 }]
                    }
                  ]
                }
              ],
              "trials": [
                {
                  "id": "trial-1",
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
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
        assertNotNull(config.sweeps());
        assertEquals(1, config.sweeps().size());

        SweepConfig sweep = config.sweeps().get(0);
        assertEquals("sweep-001", sweep.id());
        assertEquals("trial-1", sweep.baseTrialId());
        assertEquals("Comprehensive parameter sweep", sweep.description());
        assertEquals(7, sweep.parameters().size());

        assertEquals(
                "/calibrationConfig/parallelSources", sweep.parameters().get(0).path());
        assertEquals(3, sweep.parameters().get(0).values().size());
        assertEquals(2, sweep.parameters().get(0).values().get(0).asInt());

        assertEquals(
                "/calibrationConfig/totalRequiredExecutions",
                sweep.parameters().get(1).path());
        assertEquals(5000000000L, sweep.parameters().get(1).values().get(1).asLong());

        assertEquals(
                "/calibrationConfig/observeCycleStart",
                sweep.parameters().get(2).path());
        assertTrue(sweep.parameters().get(2).values().get(0).asBoolean());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }

    /// Verifies blank sweep id, baseTrialId, or description are rejected.
    @Test
    void rejectBlankSweepIdBaseTrialIdAndDescription() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepConfig(
                        "  ",
                        "base-1",
                        "desc",
                        List.of(new SweepParameter("/calibrationConfig/p", List.of(new IntNode(1))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepConfig(
                        "",
                        "base-1",
                        "desc",
                        List.of(new SweepParameter("/calibrationConfig/p", List.of(new IntNode(1))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepConfig(
                        "id",
                        "   ",
                        "desc",
                        List.of(new SweepParameter("/calibrationConfig/p", List.of(new IntNode(1))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepConfig(
                        "id",
                        "base-1",
                        "   ",
                        List.of(new SweepParameter("/calibrationConfig/p", List.of(new IntNode(1))))));
    }

    /// Verifies duplicate sweep IDs across sweeps list are rejected.
    @Test
    void rejectDuplicateSweepId() {
        SweepConfig sweep1 = new SweepConfig(
                "sweep-1",
                "trial-1",
                null,
                List.of(new SweepParameter("/calibrationConfig/p1", List.of(new IntNode(1)))));
        SweepConfig sweep2 = new SweepConfig(
                "sweep-1",
                "trial-1",
                null,
                List.of(new SweepParameter("/calibrationConfig/p2", List.of(new IntNode(2)))));

        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(sweep1, sweep2),
                        List.of(dummyTrialConfigWithId("trial-1"))));
    }

    /// Verifies null or empty parameters in SweepConfig are rejected.
    @Test
    void rejectNullOrEmptySweepParameters() {
        assertThrows(NullPointerException.class, () -> new SweepConfig("sweep-1", "base-1", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SweepConfig("sweep-1", "base-1", List.of()));
    }

    /// Verifies invalid repetitions in SweepConfig are rejected.
    @Test
    void rejectInvalidSweepConfigRepetitions() {
        SweepParameter param = new SweepParameter("/calibrationConfig/p", List.of(new IntNode(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepConfig("sweep-1", "base-1", "desc", true, 0, null, null, List.of(param)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepConfig("sweep-1", "base-1", "desc", true, -2, null, null, List.of(param)));
    }

    /// Verifies blank group in SweepConfig is rejected.
    @Test
    void rejectBlankSweepConfigGroup() {
        SweepParameter param = new SweepParameter("/calibrationConfig/p", List.of(new IntNode(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepConfig("sweep-1", "base-1", "desc", true, 1, "   ", null, List.of(param)));
    }

    /// Verifies blank label key or value in SweepConfig is rejected.
    @Test
    void rejectBlankSweepConfigLabelKeysOrValues() {
        SweepParameter param = new SweepParameter("/calibrationConfig/p", List.of(new IntNode(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepConfig("sweep-1", "base-1", "desc", true, 1, "grp", Map.of(" ", "val"), List.of(param)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepConfig(
                        "sweep-1", "base-1", "desc", true, 1, "grp", Map.of("key", "   "), List.of(param)));
    }

    /// Verifies blank parameter path or description is rejected.
    @Test
    void rejectBlankSweepParameterPathOrDescription() {
        assertThrows(IllegalArgumentException.class, () -> new SweepParameter("   ", List.of(new IntNode(1))));
        assertThrows(IllegalArgumentException.class, () -> new SweepParameter("", List.of(new IntNode(1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SweepParameter("/calibrationConfig/p", "   ", List.of(new IntNode(1))));
    }

    /// Verifies duplicate parameter paths inside one sweep are rejected.
    @Test
    void rejectDuplicateParameterPathInSweep() {
        SweepParameter param1 = new SweepParameter("/calibrationConfig/pathA", List.of(new IntNode(1)));
        SweepParameter param2 = new SweepParameter("/calibrationConfig/pathA", List.of(new IntNode(2)));

        assertThrows(
                IllegalArgumentException.class, () -> new SweepConfig("sweep-1", "base-1", List.of(param1, param2)));
    }

    /// Verifies null or empty sweep parameter values are rejected.
    @Test
    void rejectNullOrEmptySweepParameterValues() {
        assertThrows(NullPointerException.class, () -> new SweepParameter("path", null));
        assertThrows(IllegalArgumentException.class, () -> new SweepParameter("path", List.of()));
        assertThrows(
                IllegalArgumentException.class, () -> new SweepParameter("path", Arrays.asList(new IntNode(1), null)));

        String jsonNullValue = """
            {
              "sweeps": [
                {
                  "id": "sweep-1",
                  "baseTrialId": "trial-1",
                  "parameters": [
                    {
                      "path": "/calibrationConfig/pathA",
                      "values": [1, null]
                    }
                  ]
                }
              ],
              "trials": []
            }
            """;
        assertThrows(Exception.class, () -> mapper.readValue(jsonNullValue, HarnessConfig.class));
    }

    /// Verifies searches JSON parsing and round-trip equivalence across all strategy types.
    @Test
    void parseSearchesAndRoundTrip() throws Exception {
        String json = """
            {
              "sweeps": [
                {
                  "id": "sweep-1",
                  "baseTrialId": "trial-1",
                  "parameters": [
                    {
                      "path": "/calibrationConfig/parallelSources",
                      "values": [2, 4]
                    }
                  ]
                }
              ],
              "searches": [
                {
                  "id": "search-grid",
                  "strategy": "GRID",
                  "maxTrials": 10,
                  "seed": 42,
                  "objective": "maximize_throughput",
                  "sweepIds": ["sweep-1"],
                  "metadata": { "optimizer": "internal" }
                },
                {
                  "id": "search-ext",
                  "strategy": "EXTERNAL",
                  "maxTrials": 50,
                  "objective": "minimize_latency",
                  "metadata": { "source": "sol" }
                },
                {
                  "id": "search-sobol",
                  "strategy": "SOBOL",
                  "maxTrials": 20
                },
                {
                  "id": "search-random",
                  "strategy": "RANDOM",
                  "maxTrials": 30
                }
              ],
              "trials": [
                {
                  "id": "trial-1",
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
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
        assertNotNull(config.searches());
        assertEquals(4, config.searches().size());

        SearchConfig search1 = config.searches().get(0);
        assertEquals("search-grid", search1.id());
        assertEquals(SearchStrategy.GRID, search1.strategy());
        assertEquals(10, search1.maxTrials());
        assertEquals(Long.valueOf(42L), search1.seed());
        assertEquals("maximize_throughput", search1.objective());
        assertEquals(List.of("sweep-1"), search1.sweepIds());
        assertEquals(Map.of("optimizer", "internal"), search1.metadata());

        SearchConfig search2 = config.searches().get(1);
        assertEquals("search-ext", search2.id());
        assertEquals(SearchStrategy.EXTERNAL, search2.strategy());
        assertEquals(50, search2.maxTrials());

        SearchConfig search3 = config.searches().get(2);
        assertEquals(SearchStrategy.SOBOL, search3.strategy());

        SearchConfig search4 = config.searches().get(3);
        assertEquals(SearchStrategy.RANDOM, search4.strategy());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }

    /// Verifies maxTrials <= 0 is rejected.
    @Test
    void rejectInvalidMaxTrials() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SearchConfig("id", SearchStrategy.GRID, 0, null, null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SearchConfig("id", SearchStrategy.GRID, -5, null, null, null, null));
    }

    /// Verifies blank search id or objective are rejected.
    @Test
    void rejectBlankSearchIdAndObjective() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SearchConfig("  ", SearchStrategy.GRID, 10, null, null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SearchConfig("id", SearchStrategy.GRID, 10, null, "   ", null, null));
    }

    /// Verifies duplicate search IDs across searches list are rejected.
    @Test
    void rejectDuplicateSearchId() {
        SearchConfig search1 = new SearchConfig("search-1", SearchStrategy.GRID, 10, null, null, null, null);
        SearchConfig search2 = new SearchConfig("search-1", SearchStrategy.RANDOM, 10, null, null, null, null);

        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(search1, search2),
                        List.of(dummyTrialConfig())));
    }

    /// Verifies referenced sweepIds in search must exist in declared sweeps.
    @Test
    void rejectUnreferencedSweepId() {
        SweepConfig sweep1 = new SweepConfig(
                "sweep-1",
                "trial-1",
                null,
                List.of(new SweepParameter("/calibrationConfig/path", List.of(new IntNode(1)))));
        SearchConfig search =
                new SearchConfig("search-1", SearchStrategy.GRID, 10, null, null, List.of("sweep-missing"), null);

        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(sweep1),
                        List.of(search),
                        List.of(dummyTrialConfig())));
    }

    /// Verifies defensive copying of search collections.
    @Test
    void defensivelyCopySearchCollections() {
        List<String> mutableSweepIds = Arrays.asList("sweep-1");
        Map<String, String> mutableMetadata = new HashMap<>();
        mutableMetadata.put("key", "val");

        SearchConfig search =
                new SearchConfig("search-1", SearchStrategy.GRID, 10, null, null, mutableSweepIds, mutableMetadata);

        assertNotNull(search.sweepIds());
        assertThrows(
                UnsupportedOperationException.class, () -> search.sweepIds().add("sweep-2"));

        assertNotNull(search.metadata());
        assertThrows(
                UnsupportedOperationException.class, () -> search.metadata().put("k2", "v2"));
    }

    /// Verifies TrialOrigin parsing and round-trip equivalence across all origin types.
    @Test
    void parseTrialOriginAndRoundTrip() throws Exception {
        String json = """
            {
              "trials": [
                {
                  "id": "trial-sweep",
                  "origin": {
                    "type": "SWEEP",
                    "sourceId": "sweep-001",
                    "seed": 12345,
                    "candidateIndex": 0
                  },
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
                    "observeCycleStart": false,
                    "observeBatchProgress": false,
                    "observeBatchComplete": false,
                    "observeRawBodyCost": false,
                    "observeIdleDecision": false,
                    "observeExecDecision": false
                  }
                },
                {
                  "id": "trial-manual",
                  "origin": {
                    "type": "MANUAL"
                  },
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
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
        assertEquals(2, config.trials().size());

        TrialConfig trial1 = config.trials().get(0);
        assertNotNull(trial1.origin());
        assertEquals(OriginType.SWEEP, trial1.origin().type());
        assertEquals("sweep-001", trial1.origin().sourceId());
        assertEquals(Long.valueOf(12345L), trial1.origin().seed());
        assertEquals(Integer.valueOf(0), trial1.origin().candidateIndex());

        TrialConfig trial2 = config.trials().get(1);
        assertNotNull(trial2.origin());
        assertEquals(OriginType.MANUAL, trial2.origin().type());
        assertNull(trial2.origin().sourceId());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }

    /// Verifies MANUAL origin type requires no sourceId.
    @Test
    void rejectManualWithSourceId() {
        assertThrows(IllegalArgumentException.class, () -> new TrialOrigin(OriginType.MANUAL, "source-1", null, null));
    }

    /// Verifies blank sourceId is rejected.
    @Test
    void rejectBlankOriginSourceId() {
        assertThrows(IllegalArgumentException.class, () -> new TrialOrigin(OriginType.SWEEP, "   ", null, null));
        assertThrows(IllegalArgumentException.class, () -> new TrialOrigin(OriginType.SWEEP, "", null, null));
    }

    /// Verifies candidateIndex < 0 is rejected.
    @Test
    void rejectNegativeCandidateIndex() {
        assertThrows(IllegalArgumentException.class, () -> new TrialOrigin(OriginType.SWEEP, "sweep-1", null, -1));
    }

    /// Verifies candidateIndex >= 0 and null fields are allowed.
    @Test
    void allowZeroCandidateIndexAndNullFields() {
        TrialOrigin origin = new TrialOrigin(OriginType.EXTERNAL, null, null, 0);
        assertEquals(OriginType.EXTERNAL, origin.type());
        assertNull(origin.sourceId());
        assertNull(origin.seed());
        assertEquals(Integer.valueOf(0), origin.candidateIndex());
    }

    /// Verifies trial metadata deserialization and round-trip equivalence with ComparisonConfig.
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
                  "comparison": {
                    "baselineTrialId": null,
                    "comparisonGroup": "group-a",
                    "purpose": "Baseline comparison"
                  },
                  "tags": ["tag1", "tag2"],
                  "enabled": true,
                  "forks": 1,
                  "warmups": 1,
                  "iterations": 5,
                  "calibrationConfig": {
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
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
        TrialConfig trial = config.trials().get(0);

        assertEquals("trial-1", trial.id());
        assertEquals("Trial One", trial.name());
        assertEquals("group-a", trial.group());
        assertEquals("First trial", trial.description());
        assertEquals("Hypothesis A", trial.hypothesis());
        assertNotNull(trial.comparison());
        assertNull(trial.comparison().baselineTrialId());
        assertEquals("group-a", trial.comparison().comparisonGroup());
        assertEquals("Baseline comparison", trial.comparison().purpose());
        assertEquals(List.of("tag1", "tag2"), trial.tags());
        assertEquals(Boolean.TRUE, trial.enabled());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }

    /// Verifies trial warmupTime and measurementTime deserialization and round-trip equivalence.
    @Test
    void parseTrialWarmupAndMeasurementTimeAndRoundTrip() throws Exception {
        String json = """
            {
              "trials": [
                {
                  "id": "trial-timed",
                  "forks": 1,
                  "warmups": 2,
                  "iterations": 3,
                  "warmupTime": "1s",
                  "measurementTime": "5s",
                  "calibrationConfig": {
                    "cpuSet": [1, 2, 3, 4],
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
                    "rawSampleLimit": 1024,
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
        TrialConfig trial = config.trials().get(0);
        assertEquals("1s", trial.warmupTime());
        assertEquals("5s", trial.measurementTime());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
        assertEquals("1s", roundTrip.trials().get(0).warmupTime());
        assertEquals("5s", roundTrip.trials().get(0).measurementTime());
    }

    /// Verifies blank warmupTime and measurementTime in TrialConfig are rejected.
    @Test
    void rejectBlankWarmupAndMeasurementTime() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrialConfig(1, 1, 1, "  ", "5s", null, dummyCalibrationConfig()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrialConfig(1, 1, 1, "1s", "   ", null, dummyCalibrationConfig()));
    }

    /// Verifies blank fields in ComparisonConfig are rejected.
    @Test
    void rejectBlankComparisonConfigFields() {
        assertThrows(IllegalArgumentException.class, () -> new ComparisonConfig("  ", "group", "purpose"));
        assertThrows(IllegalArgumentException.class, () -> new ComparisonConfig("baseline", "  ", "purpose"));
        assertThrows(IllegalArgumentException.class, () -> new ComparisonConfig("baseline", "group", "  "));
    }

    /// Verifies valid baseline reference between trials.
    @Test
    void validBaselineReference() {
        TrialConfig baselineTrial = dummyTrialConfigWithId("trial-1");
        TrialConfig comparingTrial = dummyTrialConfigWithComparison(
                "trial-2", new ComparisonConfig("trial-1", "contention-group", "Compare throughput"));

        HarnessConfig config = new HarnessConfig(List.of(baselineTrial, comparingTrial));
        assertEquals(2, config.trials().size());
        assertEquals("trial-1", config.trials().get(1).comparison().baselineTrialId());
    }

    /// Verifies error when trial references a missing baseline ID.
    @Test
    void missingBaselineReference() {
        TrialConfig trial = dummyTrialConfigWithComparison(
                "trial-2", new ComparisonConfig("non-existent-id", "contention-group", "Compare throughput"));

        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(List.of(trial)));
    }

    /// Verifies error when trial references itself as baseline.
    @Test
    void selfReferenceBaseline() {
        TrialConfig trial = dummyTrialConfigWithComparison(
                "trial-1", new ComparisonConfig("trial-1", "contention-group", "Self comparison"));

        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(List.of(trial)));
    }

    /// Verifies comparison group without a baseline ID is valid.
    @Test
    void comparisonGroupsWithoutABaseline() {
        TrialConfig trial1 =
                dummyTrialConfigWithComparison("trial-1", new ComparisonConfig(null, "group-alpha", "Group member 1"));
        TrialConfig trial2 =
                dummyTrialConfigWithComparison("trial-2", new ComparisonConfig(null, "group-alpha", "Group member 2"));

        HarnessConfig config = new HarnessConfig(List.of(trial1, trial2));
        assertEquals(2, config.trials().size());
        assertNull(config.trials().get(0).comparison().baselineTrialId());
        assertEquals("group-alpha", config.trials().get(0).comparison().comparisonGroup());
    }

    /// Verifies blank trial metadata fields (id, name, group, description, hypothesis, tags, jvmArgs) are rejected.
    @Test
    void rejectBlankTrialMetadataFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrialConfig(
                        "  ",
                        "name",
                        "group",
                        "desc",
                        "hyp",
                        null,
                        null,
                        true,
                        1,
                        1,
                        1,
                        null,
                        dummyCalibrationConfig()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrialConfig(
                        "id", "  ", "group", "desc", "hyp", null, null, true, 1, 1, 1, null, dummyCalibrationConfig()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrialConfig(
                        "id", "name", "  ", "desc", "hyp", null, null, true, 1, 1, 1, null, dummyCalibrationConfig()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrialConfig(
                        "id", "name", "group", "  ", "hyp", null, null, true, 1, 1, 1, null, dummyCalibrationConfig()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrialConfig(
                        "id",
                        "name",
                        "group",
                        "desc",
                        "  ",
                        null,
                        null,
                        true,
                        1,
                        1,
                        1,
                        null,
                        dummyCalibrationConfig()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrialConfig(
                        "id",
                        "name",
                        "group",
                        "desc",
                        "hyp",
                        null,
                        List.of("  "),
                        true,
                        1,
                        1,
                        1,
                        null,
                        dummyCalibrationConfig()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrialConfig(
                        "id",
                        "name",
                        "group",
                        "desc",
                        "hyp",
                        null,
                        null,
                        true,
                        1,
                        1,
                        1,
                        List.of("  "),
                        dummyCalibrationConfig()));
    }

    /// Verifies tags and jvmArgs are defensively copied.
    @Test
    void defensivelyCopyTagsAndJvmArgs() {
        List<String> mutableTags = Arrays.asList("tag1", "tag2");
        List<String> mutableJvmArgs = Arrays.asList("-Xmx1g", "-Xms1g");

        TrialConfig trial = new TrialConfig(
                "id",
                "name",
                "group",
                null,
                null,
                null,
                mutableTags,
                true,
                null,
                1,
                1,
                5,
                mutableJvmArgs,
                dummyCalibrationConfig());

        assertNotNull(trial.tags());
        assertThrows(UnsupportedOperationException.class, () -> trial.tags().add("tag3"));

        assertNotNull(trial.jvmArgs());
        assertThrows(UnsupportedOperationException.class, () -> trial.jvmArgs().add("-XX:+UseG1GC"));
    }

    /// Verifies duplicate trial IDs are rejected at the HarnessConfig level.
    @Test
    void rejectDuplicateTrialIds() {
        TrialConfig trial1 = dummyTrialConfigWithId("trial-1");
        TrialConfig trial2 = dummyTrialConfigWithId("trial-1");

        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(List.of(trial1, trial2)));
    }

    /// Verifies multiple trials with unique or null IDs are accepted.
    @Test
    void allowMultipleTrialsWithUniqueOrNullIds() {
        TrialConfig trial1 = dummyTrialConfigWithId("trial-1");
        TrialConfig trial2 = dummyTrialConfigWithId("trial-2");
        TrialConfig trial3 = dummyTrialConfigWithId(null);
        TrialConfig trial4 = dummyTrialConfigWithId(null);

        HarnessConfig config = new HarnessConfig(List.of(trial1, trial2, trial3, trial4));
        assertEquals(4, config.trials().size());
    }
    /// Verifies test calibration JSON file test_calibration.json parses correctly and passes round-trip.
    @Test
    void parseTestCalibrationConfigAndRoundTrip() throws Exception {
        File file = new File("src/test/resources/test_calibration.json");
        if (!file.exists()) {
            file = new File("benchmarks/src/test/resources/test_calibration.json");
        }
        assertTrue(file.exists(), "test_calibration.json should exist");

        HarnessConfig config = mapper.readValue(file, HarnessConfig.class);
        assertEquals(1, config.schemaVersion());
        assertEquals("exec-contention-band-calibration", config.id());
        assertEquals("Execution Contention Band Calibration", config.name());
        assertNotNull(config.trials());
        assertEquals(1, config.trials().size());
        assertNotNull(config.sweeps());
        assertEquals(1, config.sweeps().size());

        HarnessConfig expanded = TrialSweepExpander.expandHarnessConfig(config);
        assertEquals(3, expanded.trials().size());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }
}
