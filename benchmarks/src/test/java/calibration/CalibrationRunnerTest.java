package calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.HarnessConfig.ArtifactConfig;
import calibration.config.HarnessConfig.TrialConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Unit tests for CalibrationRunner artifact directory preparation and JVM property wiring.
class CalibrationRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static CalibrationBenchmarkConfig dummyCalibrationConfig() {
        return new CalibrationBenchmarkConfig(
                4,
                2,
                100,
                true,
                1000000,
                60000,
                FragmentDecisionWeights.DEFAULT,
                false,
                false,
                false,
                false,
                false,
                false);
    }

    private static TrialConfig dummyTrialConfig(String id) {
        return new TrialConfig(
                id, "trial-name", "group", null, null, null, null, true, 1, 1, 1, null, dummyCalibrationConfig());
    }

    @Test
    void prepareInvocationDirectoryCreatesChildDirAndWritesConfigWhenRetainExpandedConfigIsTrue(@TempDir Path tempDir)
            throws Exception {
        File baseDir = tempDir.resolve("output").toFile();
        ArtifactConfig artifacts = new ArtifactConfig(baseDir.getPath(), true, false, false, false, false);

        File resolvedBaseDir = baseDir.getCanonicalFile();
        resolvedBaseDir.mkdirs();

        TrialConfig trial = dummyTrialConfig("t1");
        List<String> jvmArgs = new ArrayList<>();

        File invocationDir =
                CalibrationRunner.prepareInvocationDirectory(trial, 0, 1, mapper, artifacts, resolvedBaseDir, jvmArgs);

        assertNotNull(invocationDir);
        assertTrue(invocationDir.exists());
        assertEquals(new File(resolvedBaseDir, "t1_repeat_1").getCanonicalPath(), invocationDir.getCanonicalPath());

        assertTrue(jvmArgs.contains("-Deuhedral.calibration.outputDirectory=" + invocationDir.getCanonicalPath()));

        File configFile = new File(invocationDir, "trial_config.json");
        assertTrue(configFile.exists());
        TrialConfig readTrial = mapper.readValue(configFile, TrialConfig.class);
        assertEquals("t1", readTrial.id());
    }

    @Test
    void prepareInvocationDirectoryUsesIndexWhenIdIsNull(@TempDir Path tempDir) throws Exception {
        File baseDir = tempDir.resolve("output").toFile();
        ArtifactConfig artifacts = new ArtifactConfig(baseDir.getPath(), false, false, false, false, false);

        File resolvedBaseDir = baseDir.getCanonicalFile();
        resolvedBaseDir.mkdirs();

        TrialConfig trial = dummyTrialConfig(null);
        List<String> jvmArgs = new ArrayList<>();

        File invocationDir =
                CalibrationRunner.prepareInvocationDirectory(trial, 3, 0, mapper, artifacts, resolvedBaseDir, jvmArgs);

        assertNotNull(invocationDir);
        assertTrue(invocationDir.exists());
        assertEquals(new File(resolvedBaseDir, "3_repeat_0").getCanonicalPath(), invocationDir.getCanonicalPath());

        assertTrue(jvmArgs.contains("-Deuhedral.calibration.outputDirectory=" + invocationDir.getCanonicalPath()));

        File configFile = new File(invocationDir, "trial_config.json");
        assertFalse(configFile.exists());
    }

    @Test
    void prepareInvocationDirectoryReturnsNullWhenBaseOutputDirIsNull() throws Exception {
        TrialConfig trial = dummyTrialConfig("t1");
        List<String> jvmArgs = new ArrayList<>();

        File invocationDir = CalibrationRunner.prepareInvocationDirectory(trial, 0, 0, mapper, null, null, jvmArgs);

        assertNull(invocationDir);
        assertTrue(jvmArgs.isEmpty());
    }
}
