package calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.HarnessConfig;
import calibration.config.HarnessConfig.ArtifactConfig;
import calibration.config.HarnessConfig.HarnessRunOptions;
import calibration.config.HarnessConfig.TrialConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Unit tests for CalibrationRunner refactored private operations.
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

    private static TrialConfig dummyTrialConfig(String id, boolean enabled) {
        return new TrialConfig(
                id,
                "trial-name",
                "group",
                null,
                null,
                null,
                null,
                enabled,
                1,
                1,
                1,
                List.of("-Xmx1g"),
                dummyCalibrationConfig());
    }

    @Test
    void resolveOutputDirectoryCreatesAndReturnsCanonicalDirWhenConfigured(@TempDir Path tempDir) throws Exception {
        Path targetPath = tempDir.resolve("reports/benchmarks");
        ArtifactConfig artifacts = new ArtifactConfig(targetPath.toString(), true, false, false, false, false);

        File resolved = CalibrationRunner.resolveOutputDirectory(artifacts);

        assertNotNull(resolved);
        assertTrue(resolved.exists());
        assertEquals(targetPath.toFile().getCanonicalPath(), resolved.getCanonicalPath());
    }

    @Test
    void resolveOutputDirectoryReturnsNullWhenNullOrBlank() throws Exception {
        assertNull(CalibrationRunner.resolveOutputDirectory(null));
        assertNull(
                CalibrationRunner.resolveOutputDirectory(new ArtifactConfig(null, true, false, false, false, false)));
    }

    @Test
    void resolveTrialsFiltersDisabledTrials() {
        TrialConfig enabledTrial = dummyTrialConfig("t1", true);
        TrialConfig disabledTrial = dummyTrialConfig("t2", false);

        HarnessConfig harnessConfig = new HarnessConfig(
                null, null, null, null, null, null, null, null, null, null, null, List.of(enabledTrial, disabledTrial));

        List<TrialConfig> activeTrials = CalibrationRunner.resolveTrials(harnessConfig);

        assertEquals(1, activeTrials.size());
        assertEquals("t1", activeTrials.getFirst().id());
    }

    @Test
    void resolveTrialsShufflesWhenRandomizeTrialOrderIsTrue() {
        TrialConfig t1 = dummyTrialConfig("t1", true);
        TrialConfig t2 = dummyTrialConfig("t2", true);
        TrialConfig t3 = dummyTrialConfig("t3", true);

        HarnessRunOptions options = new HarnessRunOptions(true, 42L, true, 1);
        HarnessConfig harnessConfig = new HarnessConfig(
                null, null, null, null, null, options, null, null, null, null, null, List.of(t1, t2, t3));

        List<TrialConfig> activeTrials = CalibrationRunner.resolveTrials(harnessConfig);

        assertEquals(3, activeTrials.size());
    }

    @Test
    void buildJvmArgsMaintainsDeterministicOrdering(@TempDir Path tempDir) throws Exception {
        TrialConfig trial = dummyTrialConfig("t1", true);
        File invocationDir = tempDir.resolve("inv_0").toFile();
        invocationDir.mkdirs();

        List<String> jvmArgs = CalibrationRunner.buildJvmArgs(trial, 0, 1, "/tmp/config.json", invocationDir);

        assertTrue(jvmArgs.contains("-XX:+UseThreadPriorities"));
        assertTrue(jvmArgs.contains("-Deuhedral.calibration.trialConfigPath=/tmp/config.json"));
        assertTrue(jvmArgs.contains("-Deuhedral.calibration.trialIndex=0"));
        assertTrue(jvmArgs.contains("-Deuhedral.calibration.repeatIndex=1"));
        assertTrue(jvmArgs.contains("-Deuhedral.calibration.trialId=t1"));
        assertTrue(jvmArgs.contains("-Deuhedral.calibration.trialName=trial-name"));
        assertTrue(jvmArgs.contains("-Deuhedral.calibration.outputDirectory=" + invocationDir.getCanonicalPath()));
        assertTrue(jvmArgs.contains("-Xmx1g"));

        int configIdx = jvmArgs.indexOf("-Deuhedral.calibration.trialConfigPath=/tmp/config.json");
        int outputDirIdx =
                jvmArgs.indexOf("-Deuhedral.calibration.outputDirectory=" + invocationDir.getCanonicalPath());
        int customArgIdx = jvmArgs.indexOf("-Xmx1g");

        assertTrue(configIdx < outputDirIdx);
        assertTrue(outputDirIdx < customArgIdx);
    }

    @Test
    void prepareInvocationDirectoryCreatesChildDirAndWritesConfigWhenRetainExpandedConfigIsTrue(@TempDir Path tempDir)
            throws Exception {
        File baseDir = tempDir.resolve("output").toFile();
        ArtifactConfig artifacts = new ArtifactConfig(baseDir.getPath(), true, false, false, false, false);

        File resolvedBaseDir = baseDir.getCanonicalFile();
        resolvedBaseDir.mkdirs();

        TrialConfig trial = dummyTrialConfig("t1", true);

        File invocationDir =
                CalibrationRunner.prepareInvocationDirectory(trial, 0, 1, mapper, artifacts, resolvedBaseDir);

        assertNotNull(invocationDir);
        assertTrue(invocationDir.exists());
        assertEquals(new File(resolvedBaseDir, "t1_repeat_1").getCanonicalPath(), invocationDir.getCanonicalPath());

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

        TrialConfig trial = dummyTrialConfig(null, true);

        File invocationDir =
                CalibrationRunner.prepareInvocationDirectory(trial, 3, 0, mapper, artifacts, resolvedBaseDir);

        assertNotNull(invocationDir);
        assertTrue(invocationDir.exists());
        assertEquals(new File(resolvedBaseDir, "3_repeat_0").getCanonicalPath(), invocationDir.getCanonicalPath());

        File configFile = new File(invocationDir, "trial_config.json");
        assertFalse(configFile.exists());
    }

    @Test
    void prepareInvocationDirectoryReturnsNullWhenBaseOutputDirIsNull() throws Exception {
        TrialConfig trial = dummyTrialConfig("t1", true);

        File invocationDir = CalibrationRunner.prepareInvocationDirectory(trial, 0, 0, mapper, null, null);

        assertNull(invocationDir);
    }
}
