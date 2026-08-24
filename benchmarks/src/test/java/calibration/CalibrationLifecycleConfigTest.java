package calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.CalibrationLifecycleMode;
import calibration.config.HarnessConfig;
import calibration.config.TrialConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CalibrationLifecycleConfigTest {

    private static final String CALIBRATION_FIELDS = """
            "cpuSet": [2, 4, 6, 8],
            "parallelSources": 2,
            "orderedSources": 0,
            "workUnits": 0,
            "randomizeWork": false,
            "totalRequiredExecutions": 1000,
            "invocationTimeoutMillis": 10000,
            "decisionWeights": {
              "idleBodyCostWeights": { "xs": 96, "s": 128, "m": 216, "h": 288 },
              "idleTimeNs": { "xsPark": 1000, "sPark": 0, "mPark": 5000, "hPark": 5000, "xhPark": 5000 }
            },
            "observeCycleStart": true,
            "observeIdleDecision": true,
            "observeExecDecision": true,
            "observeContentionStaleness": true
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oldConfigDefaultsToResetAndResolvedSerializationPersistsIt() throws Exception {
        CalibrationBenchmarkConfig config =
                this.mapper.readValue("{%s}".formatted(CALIBRATION_FIELDS), CalibrationBenchmarkConfig.class);

        assertEquals(CalibrationLifecycleMode.RESET, config.lifecycleMode());
        String serialized = this.mapper.writeValueAsString(config);
        assertTrue(serialized.contains("\"lifecycleMode\":\"RESET\""));
        assertEquals(
                CalibrationLifecycleMode.RESET,
                this.mapper
                        .readValue(serialized, CalibrationBenchmarkConfig.class)
                        .lifecycleMode());
    }

    @Test
    void explicitResetAndContinuousParseWhileInvalidValueFails() throws Exception {
        CalibrationBenchmarkConfig reset = this.mapper.readValue(
                "{%s,\"lifecycleMode\":\"RESET\"}".formatted(CALIBRATION_FIELDS), CalibrationBenchmarkConfig.class);
        CalibrationBenchmarkConfig continuous = this.mapper.readValue(
                "{%s,\"lifecycleMode\":\"CONTINUOUS\"}".formatted(CALIBRATION_FIELDS),
                CalibrationBenchmarkConfig.class);

        assertEquals(CalibrationLifecycleMode.RESET, reset.lifecycleMode());
        assertEquals(CalibrationLifecycleMode.CONTINUOUS, continuous.lifecycleMode());
        assertThrows(
                Exception.class,
                () -> this.mapper.readValue(
                        "{%s,\"lifecycleMode\":\"STEADY\"}".formatted(CALIBRATION_FIELDS),
                        CalibrationBenchmarkConfig.class));
    }

    @Test
    void importedProfilePreservesLifecycleAndSweepOverridesIt(@TempDir Path tempDir) throws Exception {
        Path library = tempDir.resolve("library.json");
        Files.writeString(library, """
                {
                  "calibrationProfiles": {
                    "persistent": {
                      %s,
                      "lifecycleMode": "CONTINUOUS"
                    }
                  }
                }
                """.formatted(CALIBRATION_FIELDS), StandardCharsets.UTF_8);
        Path harnessFile = tempDir.resolve("harness.json");
        Files.writeString(harnessFile, """
                {
                  "imports": [{"path": "library.json", "namespace": "shared"}],
                  "sweeps": [{
                    "id": "reset-override",
                    "baseTrialId": "base",
                    "parameters": [{"path": "/calibrationConfig/lifecycleMode", "values": ["RESET"]}]
                  }],
                  "trials": [{
                    "id": "base",
                    "enabled": false,
                    "forks": 1,
                    "warmups": 1,
                    "iterations": 2,
                    "calibrationProfile": "shared.persistent"
                  }]
                }
                """, StandardCharsets.UTF_8);

        HarnessConfig harness = CalibrationRunner.loadConfig(harnessFile.toString(), this.mapper);
        List<TrialConfig> resolved = CalibrationRunner.resolveTrials(harness, this.mapper);

        assertEquals(1, resolved.size());
        assertEquals(
                CalibrationLifecycleMode.RESET,
                resolved.getFirst().calibrationConfig().lifecycleMode());

        TrialConfig imported = harness.resolveCalibrationProfiles().trials().getFirst();
        assertEquals(
                CalibrationLifecycleMode.CONTINUOUS,
                imported.calibrationConfig().lifecycleMode());
        Path completedConfig = tempDir.resolve("trial_config.json");
        CalibrationRunner.writeTrialConfig(this.mapper, completedConfig.toFile(), imported);
        assertEquals(
                CalibrationLifecycleMode.CONTINUOUS,
                this.mapper
                        .readValue(completedConfig.toFile(), TrialConfig.class)
                        .calibrationConfig()
                        .lifecycleMode());
    }
}
