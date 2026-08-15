package calibration.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private final ObjectMapper mapper = new ObjectMapper();

    private static HarnessConfig.TrialConfig dummyTrialConfig() {
        return new HarnessConfig.TrialConfig(1, 1, 1, null, dummyCalibrationConfig());
    }

    private static HarnessConfig.TrialConfig dummyTrialConfigWithId(String id) {
        return new HarnessConfig.TrialConfig(
                id, "name", "group", null, null, null, null, true, 1, 1, 1, null, dummyCalibrationConfig());
    }

    private static HarnessConfig.TrialConfig dummyTrialConfigWithComparison(
            String id, HarnessConfig.ComparisonConfig comparison) {
        return new HarnessConfig.TrialConfig(
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

        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig.HarnessRunOptions(null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig.HarnessRunOptions(null, null, null, -1));
    }

    /// Verifies HarnessRunOptions accepts any long for randomSeed and null booleans.
    @Test
    void allowAnyRandomSeedAndNullBooleans() {
        HarnessConfig.HarnessRunOptions options = new HarnessConfig.HarnessRunOptions(null, -999L, null, 1);
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

        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig.ArtifactConfig("   ", true, true, true, true, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig.ArtifactConfig("", true, true, true, true, true));
    }

    /// Verifies ArtifactConfig allows null properties.
    @Test
    void allowNullArtifactFields() {
        HarnessConfig.ArtifactConfig artifacts = new HarnessConfig.ArtifactConfig(null, null, null, null, null, null);
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
                  "description": "Comprehensive parameter sweep",
                  "parameters": [
                    {
                      "path": "calibrationConfig.parallelSources",
                      "values": [2, 4, 8]
                    },
                    {
                      "path": "calibrationConfig.totalRequiredExecutions",
                      "values": [1000, 5000000000]
                    },
                    {
                      "path": "calibrationConfig.observeCycleStart",
                      "values": [true, false]
                    },
                    {
                      "path": "calibrationConfig.ratio",
                      "values": [0.25, 0.75]
                    },
                    {
                      "path": "calibrationConfig.mode",
                      "values": ["FAST", "SLOW"]
                    },
                    {
                      "path": "calibrationConfig.tags",
                      "values": [["tagA", "tagB"], ["tagC"]]
                    },
                    {
                      "path": "calibrationConfig.weights",
                      "values": [{ "threshold": 10 }, { "threshold": 20 }]
                    }
                  ]
                }
              ],
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
        assertNotNull(config.sweeps());
        assertEquals(1, config.sweeps().size());

        HarnessConfig.SweepConfig sweep = config.sweeps().get(0);
        assertEquals("sweep-001", sweep.id());
        assertEquals("Comprehensive parameter sweep", sweep.description());
        assertEquals(7, sweep.parameters().size());

        assertEquals(
                "calibrationConfig.parallelSources", sweep.parameters().get(0).path());
        assertEquals(3, sweep.parameters().get(0).values().size());
        assertEquals(2, sweep.parameters().get(0).values().get(0).asInt());

        assertEquals(
                "calibrationConfig.totalRequiredExecutions",
                sweep.parameters().get(1).path());
        assertEquals(5000000000L, sweep.parameters().get(1).values().get(1).asLong());

        assertEquals(
                "calibrationConfig.observeCycleStart", sweep.parameters().get(2).path());
        assertTrue(sweep.parameters().get(2).values().get(0).asBoolean());

        String reSerialized = mapper.writeValueAsString(config);
        HarnessConfig roundTrip = mapper.readValue(reSerialized, HarnessConfig.class);
        assertEquals(config, roundTrip);
    }

    /// Verifies blank sweep id or description are rejected.
    @Test
    void rejectBlankSweepIdAndDescription() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig.SweepConfig(
                        "  ", "desc", List.of(new HarnessConfig.SweepParameter("path", List.of(new IntNode(1))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig.SweepConfig(
                        "", "desc", List.of(new HarnessConfig.SweepParameter("path", List.of(new IntNode(1))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig.SweepConfig(
                        "id", "   ", List.of(new HarnessConfig.SweepParameter("path", List.of(new IntNode(1))))));
    }

    /// Verifies duplicate sweep IDs across sweeps list are rejected.
    @Test
    void rejectDuplicateSweepId() {
        HarnessConfig.SweepConfig sweep1 = new HarnessConfig.SweepConfig(
                "sweep-1", null, List.of(new HarnessConfig.SweepParameter("path1", List.of(new IntNode(1)))));
        HarnessConfig.SweepConfig sweep2 = new HarnessConfig.SweepConfig(
                "sweep-1", null, List.of(new HarnessConfig.SweepParameter("path2", List.of(new IntNode(2)))));

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
                        List.of(dummyTrialConfig())));
    }

    /// Verifies null or empty parameters in SweepConfig are rejected.
    @Test
    void rejectNullOrEmptySweepParameters() {
        assertThrows(NullPointerException.class, () -> new HarnessConfig.SweepConfig("sweep-1", null, null));
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig.SweepConfig("sweep-1", null, List.of()));
    }

    /// Verifies blank parameter path is rejected.
    @Test
    void rejectBlankSweepParameterPath() {
        assertThrows(
                IllegalArgumentException.class, () -> new HarnessConfig.SweepParameter("   ", List.of(new IntNode(1))));
        assertThrows(
                IllegalArgumentException.class, () -> new HarnessConfig.SweepParameter("", List.of(new IntNode(1))));
    }

    /// Verifies duplicate parameter paths inside one sweep are rejected.
    @Test
    void rejectDuplicateParameterPathInSweep() {
        HarnessConfig.SweepParameter param1 = new HarnessConfig.SweepParameter("path.a", List.of(new IntNode(1)));
        HarnessConfig.SweepParameter param2 = new HarnessConfig.SweepParameter("path.a", List.of(new IntNode(2)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig.SweepConfig("sweep-1", null, List.of(param1, param2)));
    }

    /// Verifies null or empty sweep parameter values are rejected.
    @Test
    void rejectNullOrEmptySweepParameterValues() {
        assertThrows(NullPointerException.class, () -> new HarnessConfig.SweepParameter("path", null));
        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig.SweepParameter("path", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig.SweepParameter("path", Arrays.asList(new IntNode(1), null)));

        String jsonNullValue = """
            {
              "sweeps": [
                {
                  "id": "sweep-1",
                  "parameters": [
                    {
                      "path": "path.a",
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

    /// Verifies blank fields in ComparisonConfig are rejected.
    @Test
    void rejectBlankComparisonConfigFields() {
        assertThrows(
                IllegalArgumentException.class, () -> new HarnessConfig.ComparisonConfig("  ", "group", "purpose"));
        assertThrows(
                IllegalArgumentException.class, () -> new HarnessConfig.ComparisonConfig("baseline", "  ", "purpose"));
        assertThrows(
                IllegalArgumentException.class, () -> new HarnessConfig.ComparisonConfig("baseline", "group", "  "));
    }

    /// Verifies valid baseline reference between trials.
    @Test
    void validBaselineReference() {
        HarnessConfig.TrialConfig baselineTrial = dummyTrialConfigWithId("trial-1");
        HarnessConfig.TrialConfig comparingTrial = dummyTrialConfigWithComparison(
                "trial-2", new HarnessConfig.ComparisonConfig("trial-1", "contention-group", "Compare throughput"));

        HarnessConfig config = new HarnessConfig(List.of(baselineTrial, comparingTrial));
        assertEquals(2, config.trials().size());
        assertEquals("trial-1", config.trials().get(1).comparison().baselineTrialId());
    }

    /// Verifies error when trial references a missing baseline ID.
    @Test
    void missingBaselineReference() {
        HarnessConfig.TrialConfig trial = dummyTrialConfigWithComparison(
                "trial-2",
                new HarnessConfig.ComparisonConfig("non-existent-id", "contention-group", "Compare throughput"));

        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(List.of(trial)));
    }

    /// Verifies error when trial references itself as baseline.
    @Test
    void selfReferenceBaseline() {
        HarnessConfig.TrialConfig trial = dummyTrialConfigWithComparison(
                "trial-1", new HarnessConfig.ComparisonConfig("trial-1", "contention-group", "Self comparison"));

        assertThrows(IllegalArgumentException.class, () -> new HarnessConfig(List.of(trial)));
    }

    /// Verifies comparison group without a baseline ID is valid.
    @Test
    void comparisonGroupsWithoutABaseline() {
        HarnessConfig.TrialConfig trial1 = dummyTrialConfigWithComparison(
                "trial-1", new HarnessConfig.ComparisonConfig(null, "group-alpha", "Group member 1"));
        HarnessConfig.TrialConfig trial2 = dummyTrialConfigWithComparison(
                "trial-2", new HarnessConfig.ComparisonConfig(null, "group-alpha", "Group member 2"));

        HarnessConfig config = new HarnessConfig(List.of(trial1, trial2));
        assertEquals(2, config.trials().size());
        assertNull(config.trials().get(0).comparison().baselineTrialId());
        assertEquals("group-alpha", config.trials().get(0).comparison().comparisonGroup());
    }

    /// Verifies blank trial id, name, or group are rejected.
    @Test
    void rejectBlankTrialMetadataFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig.TrialConfig(
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
                () -> new HarnessConfig.TrialConfig(
                        "id", "  ", "group", "desc", "hyp", null, null, true, 1, 1, 1, null, dummyCalibrationConfig()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig.TrialConfig(
                        "id", "name", "  ", "desc", "hyp", null, null, true, 1, 1, 1, null, dummyCalibrationConfig()));
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
        assertNotNull(config.trials().get(0).comparison());
        assertEquals("baseline-calibration", config.trials().get(0).comparison().comparisonGroup());
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
