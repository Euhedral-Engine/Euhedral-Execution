package calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.config.ArtifactConfig;
import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.HarnessConfig;
import calibration.config.HarnessRunOptions;
import calibration.config.OriginType;
import calibration.config.SweepConfig;
import calibration.config.SweepParameter;
import calibration.config.TrialConfig;
import com.fasterxml.jackson.databind.JsonNode;
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
                List.of(1, 2, 3, 4),
                4,
                2,
                100,
                true,
                1000000,
                60000,
                FragmentDecisionWeights.DEFAULT,
                1024,
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

    private SweepParameter dummySweepParameter(String path, List<Object> values) {
        List<JsonNode> nodes =
                values.stream().map(v -> (JsonNode) mapper.valueToTree(v)).toList();
        return new SweepParameter(path, nodes);
    }

    private SweepConfig dummySweepConfig(String id, String baseTrialId, Boolean enabled, List<SweepParameter> params) {
        return new SweepConfig(id, baseTrialId, "desc", enabled, params);
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

        List<TrialConfig> activeTrials = CalibrationRunner.resolveTrials(harnessConfig, mapper);

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

        List<TrialConfig> activeTrials = CalibrationRunner.resolveTrials(harnessConfig, mapper);

        assertEquals(3, activeTrials.size());
    }

    @Test
    void resolveTrialsNoSweeps() {
        TrialConfig t1 = dummyTrialConfig("t1", true);
        TrialConfig t2 = dummyTrialConfig("t2", true);

        HarnessConfig harnessConfig =
                new HarnessConfig(null, null, null, null, null, null, null, null, null, null, null, List.of(t1, t2));

        List<TrialConfig> resolved = CalibrationRunner.resolveTrials(harnessConfig, mapper);

        assertEquals(2, resolved.size());
        assertEquals("t1", resolved.get(0).id());
        assertEquals("t2", resolved.get(1).id());
    }

    @Test
    void resolveTrialsOneEnabledSweep() {
        TrialConfig base = dummyTrialConfig("base1", true);
        SweepParameter param = dummySweepParameter("/calibrationConfig/workUnits", List.of(500, 1000));
        SweepConfig sweep = dummySweepConfig("s1", "base1", true, List.of(param));

        HarnessConfig harnessConfig = new HarnessConfig(
                null, null, null, null, null, null, null, null, null, List.of(sweep), null, List.of(base));

        List<TrialConfig> resolved = CalibrationRunner.resolveTrials(harnessConfig, mapper);

        assertEquals(3, resolved.size());
        assertEquals("base1", resolved.get(0).id());
        assertEquals("base1__s1__0", resolved.get(1).id());
        assertEquals("base1__s1__1", resolved.get(2).id());

        assertEquals(OriginType.SWEEP, resolved.get(1).origin().type());
        assertEquals("s1", resolved.get(1).origin().sourceId());
        assertEquals(500, resolved.get(1).calibrationConfig().workUnits());
        assertEquals(1000, resolved.get(2).calibrationConfig().workUnits());
    }

    @Test
    void resolveTrialsDisabledSweep() {
        TrialConfig base = dummyTrialConfig("base1", true);
        SweepParameter param = dummySweepParameter("/calibrationConfig/workUnits", List.of(500, 1000));
        SweepConfig sweep = dummySweepConfig("s1", "base1", false, List.of(param));

        HarnessConfig harnessConfig = new HarnessConfig(
                null, null, null, null, null, null, null, null, null, List.of(sweep), null, List.of(base));

        List<TrialConfig> resolved = CalibrationRunner.resolveTrials(harnessConfig, mapper);

        assertEquals(1, resolved.size());
        assertEquals("base1", resolved.get(0).id());
    }

    @Test
    void resolveTrialsDisabledTrialUsedAsTemplate() {
        TrialConfig baseDisabled = dummyTrialConfig("base1", false);
        SweepParameter param = dummySweepParameter("/calibrationConfig/workUnits", List.of(500, 1000));
        SweepConfig sweep = dummySweepConfig("s1", "base1", true, List.of(param));

        HarnessConfig harnessConfig = new HarnessConfig(
                null, null, null, null, null, null, null, null, null, List.of(sweep), null, List.of(baseDisabled));

        List<TrialConfig> resolved = CalibrationRunner.resolveTrials(harnessConfig, mapper);

        assertEquals(2, resolved.size());
        assertEquals("base1__s1__0", resolved.get(0).id());
        assertEquals("base1__s1__1", resolved.get(1).id());
        assertTrue(resolved.get(0).enabled());
        assertTrue(resolved.get(1).enabled());
    }

    @Test
    void resolveTrialsExplicitAndGeneratedTrialsTogether() {
        TrialConfig e1 = dummyTrialConfig("e1", true);
        TrialConfig e2 = dummyTrialConfig("e2", true);
        SweepParameter param = dummySweepParameter("/calibrationConfig/workUnits", List.of(100));
        SweepConfig sweep = dummySweepConfig("s1", "e1", true, List.of(param));

        HarnessConfig harnessConfig = new HarnessConfig(
                null, null, null, null, null, null, null, null, null, List.of(sweep), null, List.of(e1, e2));

        List<TrialConfig> resolved = CalibrationRunner.resolveTrials(harnessConfig, mapper);

        assertEquals(3, resolved.size());
        assertEquals("e1", resolved.get(0).id());
        assertEquals("e2", resolved.get(1).id());
        assertEquals("e1__s1__0", resolved.get(2).id());
    }

    @Test
    void resolveTrialsRandomizationAfterExpansion() {
        TrialConfig e1 = dummyTrialConfig("e1", true);
        TrialConfig e2 = dummyTrialConfig("e2", true);
        SweepParameter param = dummySweepParameter("/calibrationConfig/workUnits", List.of(100));
        SweepConfig sweep = dummySweepConfig("s1", "e1", true, List.of(param));

        HarnessRunOptions options = new HarnessRunOptions(true, 12345L, true, 1);
        HarnessConfig harnessConfig = new HarnessConfig(
                null, null, null, null, null, options, null, null, null, List.of(sweep), null, List.of(e1, e2));

        List<TrialConfig> resolved = CalibrationRunner.resolveTrials(harnessConfig, mapper);

        assertEquals(3, resolved.size());
        assertTrue(resolved.stream().anyMatch(t -> "e1".equals(t.id())));
        assertTrue(resolved.stream().anyMatch(t -> "e2".equals(t.id())));
        assertTrue(resolved.stream().anyMatch(t -> "e1__s1__0".equals(t.id())));
    }

    @Test
    void repeatsDoNotRegenerateCandidates() {
        TrialConfig base = dummyTrialConfig("base1", true);
        SweepParameter param = dummySweepParameter("/calibrationConfig/workUnits", List.of(500, 1000));
        SweepConfig sweep = dummySweepConfig("s1", "base1", true, List.of(param));

        HarnessRunOptions options = new HarnessRunOptions(false, null, true, 2);
        HarnessConfig harnessConfig = new HarnessConfig(
                null, null, null, null, null, options, null, null, null, List.of(sweep), null, List.of(base));

        List<TrialConfig> resolvedOnce = CalibrationRunner.resolveTrials(harnessConfig, mapper);

        assertEquals(3, resolvedOnce.size());
        List<String> idsRepeat0 = resolvedOnce.stream().map(TrialConfig::id).toList();
        List<String> idsRepeat1 = resolvedOnce.stream().map(TrialConfig::id).toList();
        assertEquals(idsRepeat0, idsRepeat1);
    }

    @Test
    void globalHarnessRepeatCountIndependentFromSweepRepetitions() {
        TrialConfig baseDisabled = dummyTrialConfig("base1", false);
        SweepParameter param = dummySweepParameter("/calibrationConfig/workUnits", List.of(500, 1000));
        SweepConfig sweep = new SweepConfig("s1", "base1", "desc", true, 3, null, null, List.of(param));

        HarnessRunOptions options = new HarnessRunOptions(false, null, true, 2);
        HarnessConfig harnessConfig = new HarnessConfig(
                null, null, null, null, null, options, null, null, null, List.of(sweep), null, List.of(baseDisabled));

        List<TrialConfig> resolvedTrials = CalibrationRunner.resolveTrials(harnessConfig, mapper);

        assertEquals(6, resolvedTrials.size());
        assertEquals("base1__s1__0__sample_0", resolvedTrials.get(0).id());
        assertEquals("base1__s1__0__sample_1", resolvedTrials.get(1).id());
        assertEquals("base1__s1__0__sample_2", resolvedTrials.get(2).id());
        assertEquals("base1__s1__1__sample_0", resolvedTrials.get(3).id());
        assertEquals("base1__s1__1__sample_1", resolvedTrials.get(4).id());
        assertEquals("base1__s1__1__sample_2", resolvedTrials.get(5).id());

        assertEquals(2, harnessConfig.runOptions().repeatCount());
        assertEquals(3, sweep.repetitions());
    }

    @Test
    void resolveTrialsDuplicateFinalTrialIdRejectionExplicitDuplicates() {
        TrialConfig t1 = dummyTrialConfig("dup1", true);
        TrialConfig t2 = dummyTrialConfig("dup1", true);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new HarnessConfig(
                        null, null, null, null, null, null, null, null, null, null, null, List.of(t1, t2)));
        assertTrue(ex.getMessage().contains("duplicate trial id")
                || ex.getMessage().contains("Duplicate trial ID"));
    }

    @Test
    void resolveTrialsDuplicateFinalTrialIdRejectionExplicitClashWithGenerated() {
        TrialConfig base = dummyTrialConfig("base1", true);
        TrialConfig explicitClash = dummyTrialConfig("base1__s1__0", true);
        SweepParameter param = dummySweepParameter("/calibrationConfig/workUnits", List.of(500));
        SweepConfig sweep = dummySweepConfig("s1", "base1", true, List.of(param));

        HarnessConfig harnessConfig = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(sweep),
                null,
                List.of(base, explicitClash));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> CalibrationRunner.resolveTrials(harnessConfig, mapper));
        assertTrue(ex.getMessage().contains("Duplicate trial ID"));
    }

    @Test
    void generatedTrialsFlowThroughNormalResolutionOrder() {
        TrialConfig tDisabled = dummyTrialConfig("tDisabled", false);
        TrialConfig tEnabled = dummyTrialConfig("tEnabled", true);

        SweepParameter param = dummySweepParameter("/calibrationConfig/workUnits", List.of(100, 200));
        SweepConfig sweep = dummySweepConfig("sw1", "tDisabled", true, List.of(param));

        HarnessConfig harnessConfig = new HarnessConfig(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(sweep),
                null,
                List.of(tDisabled, tEnabled));

        List<TrialConfig> resolved = CalibrationRunner.resolveTrials(harnessConfig, mapper);

        assertEquals(3, resolved.size());
        assertEquals("tEnabled", resolved.get(0).id());
        assertEquals("tDisabled__sw1__0", resolved.get(1).id());
        assertEquals("tDisabled__sw1__1", resolved.get(2).id());
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
