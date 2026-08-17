package calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.schema.ComparisonRequest;
import calibration.comparisons.schema.RunReference;
import calibration.config.ArtifactConfig;
import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.ComparisonConfig;
import calibration.config.ComparisonOptions;
import calibration.config.HarnessConfig;
import calibration.config.HarnessRunOptions;
import calibration.config.OriginType;
import calibration.config.SweepConfig;
import calibration.config.SweepParameter;
import calibration.config.TrialConfig;
import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.infra.Constants;
import calibration.io.TrialExport;
import calibration.statistics.HighSpeedMetricsStatistics;
import calibration.statistics.fork.ForkCalculationResult;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.IterationResult;
import calibration.statistics.iteration.SystemIterationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmh.runner.options.Options;

/// Unit tests for CalibrationRunner refactored operations and runner modes.
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
        assertEquals(Boolean.TRUE, resolved.get(0).enabled());
        assertEquals(Boolean.TRUE, resolved.get(1).enabled());
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

    @Test
    void resolveTrialsResolvesCalibrationProfiles() {
        TrialConfig trial1 =
                new TrialConfig("t1", "Trial 1", "grp", null, null, null, null, true, 1, 1, 5, null, "profile-alpha");
        TrialConfig trial2 = new TrialConfig(
                "t2", "Trial 2", "grp", null, null, null, null, true, 1, 1, 5, null, dummyCalibrationConfig());

        HarnessConfig config = new HarnessConfig(
                1,
                "harness-1",
                "Harness 1",
                "desc",
                null,
                null,
                null,
                Map.of("profile-alpha", dummyCalibrationConfig()),
                null,
                null,
                null,
                List.of(trial1, trial2));

        List<TrialConfig> resolved = CalibrationRunner.resolveTrials(config, mapper);
        assertEquals(2, resolved.size());

        TrialConfig resolvedTrial1 = resolved.getFirst();
        assertEquals("t1", resolvedTrial1.id());
        assertEquals("profile-alpha", resolvedTrial1.calibrationProfile());
        assertNotNull(resolvedTrial1.calibrationConfig());
        assertEquals(4, resolvedTrial1.calibrationConfig().parallelSources());
        assertEquals(100, resolvedTrial1.calibrationConfig().workUnits());

        TrialConfig resolvedTrial2 = resolved.get(1);
        assertEquals("t2", resolvedTrial2.id());
        assertNotNull(resolvedTrial2.calibrationConfig());
    }

    @Test
    void buildOptionsConfiguresOutputFileWhenRetainRawBenchmarkOutputIsTrue(@TempDir Path tempDir) throws Exception {
        File invocationDir = tempDir.resolve("trial_repeat_0").toFile();
        invocationDir.mkdirs();
        ArtifactConfig artifacts = new ArtifactConfig(tempDir.toString(), true, true, true, false, false);
        TrialConfig trial = dummyTrialConfig("t1", true);
        List<String> jvmArgs = List.of("-Xmx1g");

        Options options = CalibrationRunner.buildOptions(trial, jvmArgs, artifacts, invocationDir);

        assertTrue(options.getOutput().hasValue());
        File expectedOutputFile = new File(invocationDir, "benchmark_output.log");
        assertEquals(
                expectedOutputFile.getCanonicalPath(),
                new File(options.getOutput().get()).getCanonicalPath());
    }

    @Test
    void buildOptionsDoesNotConfigureOutputFileWhenRetainRawBenchmarkOutputIsFalse(@TempDir Path tempDir) {
        File invocationDir = tempDir.resolve("trial_repeat_0").toFile();
        invocationDir.mkdirs();
        ArtifactConfig artifacts = new ArtifactConfig(tempDir.toString(), true, false, true, false, false);
        TrialConfig trial = dummyTrialConfig("t1", true);
        List<String> jvmArgs = List.of("-Xmx1g");

        Options options = CalibrationRunner.buildOptions(trial, jvmArgs, artifacts, invocationDir);

        assertFalse(options.getOutput().hasValue());
    }

    @Test
    void buildOptionsDoesNotConfigureOutputFileWhenRetainRawBenchmarkOutputIsNull(@TempDir Path tempDir) {
        File invocationDir = tempDir.resolve("trial_repeat_0").toFile();
        invocationDir.mkdirs();
        ArtifactConfig artifacts = new ArtifactConfig(tempDir.toString(), true, null, true, false, false);
        TrialConfig trial = dummyTrialConfig("t1", true);
        List<String> jvmArgs = List.of("-Xmx1g");

        Options options = CalibrationRunner.buildOptions(trial, jvmArgs, artifacts, invocationDir);

        assertFalse(options.getOutput().hasValue());
    }

    @Test
    void buildOptionsDoesNotConfigureOutputFileWhenArtifactsIsNull(@TempDir Path tempDir) {
        File invocationDir = tempDir.resolve("trial_repeat_0").toFile();
        invocationDir.mkdirs();
        TrialConfig trial = dummyTrialConfig("t1", true);
        List<String> jvmArgs = List.of("-Xmx1g");

        Options options = CalibrationRunner.buildOptions(trial, jvmArgs, null, invocationDir);

        assertFalse(options.getOutput().hasValue());
    }

    @Test
    void buildOptionsDoesNotConfigureOutputFileWhenInvocationDirIsNull(@TempDir Path tempDir) {
        ArtifactConfig artifacts = new ArtifactConfig(tempDir.toString(), true, true, true, false, false);
        TrialConfig trial = dummyTrialConfig("t1", true);
        List<String> jvmArgs = List.of("-Xmx1g");

        Options options = CalibrationRunner.buildOptions(trial, jvmArgs, artifacts, null);

        assertFalse(options.getOutput().hasValue());
    }

    @Test
    void buildJvmArgsIncludesRetainPerForkAndIterationWhenConfigured(@TempDir Path tempDir) throws Exception {
        TrialConfig trial = dummyTrialConfig("t1", true);
        File invocationDir = tempDir.resolve("inv_0").toFile();
        invocationDir.mkdirs();
        ArtifactConfig artifacts = new ArtifactConfig(tempDir.toString(), true, true, true, true, true);

        List<String> jvmArgs =
                CalibrationRunner.buildJvmArgs(trial, 0, 1, "/tmp/config.json", invocationDir, artifacts);

        assertTrue(jvmArgs.contains("-Deuhedral.calibration.retainObserverData=true"));
        assertTrue(jvmArgs.contains("-Deuhedral.calibration.retainPerForkResults=true"));
        assertTrue(jvmArgs.contains("-Deuhedral.calibration.retainPerIterationResults=true"));
    }

    @Test
    void buildJvmArgsIncludesRetainPerForkAndIterationWhenFalse(@TempDir Path tempDir) throws Exception {
        TrialConfig trial = dummyTrialConfig("t1", true);
        File invocationDir = tempDir.resolve("inv_0").toFile();
        invocationDir.mkdirs();
        ArtifactConfig artifacts = new ArtifactConfig(tempDir.toString(), true, false, false, false, false);

        List<String> jvmArgs =
                CalibrationRunner.buildJvmArgs(trial, 0, 1, "/tmp/config.json", invocationDir, artifacts);

        assertTrue(jvmArgs.contains("-Deuhedral.calibration.retainObserverData=false"));
        assertTrue(jvmArgs.contains("-Deuhedral.calibration.retainPerForkResults=false"));
        assertTrue(jvmArgs.contains("-Deuhedral.calibration.retainPerIterationResults=false"));
    }

    @Test
    void buildJvmArgsOmitsRetainPerForkAndIterationWhenNull(@TempDir Path tempDir) throws Exception {
        TrialConfig trial = dummyTrialConfig("t1", true);
        File invocationDir = tempDir.resolve("inv_0").toFile();
        invocationDir.mkdirs();
        ArtifactConfig artifacts = new ArtifactConfig(tempDir.toString(), null, null, null, null, null);

        List<String> jvmArgs =
                CalibrationRunner.buildJvmArgs(trial, 0, 1, "/tmp/config.json", invocationDir, artifacts);

        assertFalse(jvmArgs.stream().anyMatch(arg -> arg.startsWith("-Deuhedral.calibration.retainObserverData=")));
        assertFalse(jvmArgs.stream().anyMatch(arg -> arg.startsWith("-Deuhedral.calibration.retainPerForkResults=")));
        assertFalse(
                jvmArgs.stream().anyMatch(arg -> arg.startsWith("-Deuhedral.calibration.retainPerIterationResults=")));
    }

    private static HighSpeedMetrics createPopulatedMetrics(int offset) {
        HighSpeedMetrics metrics = new HighSpeedMetrics(8);
        metrics.recordCycleStart(1, 1, 10 + offset, 5, 2, 4, 1, 100 + offset, 10.0 + offset);
        metrics.recordCycleStart(2, 2, 20 + offset, 5, 2, 4, 1, 200 + offset, 20.0 + offset);
        metrics.recordCycleStart(3, 3, 30 + offset, 5, 2, 4, 1, 300 + offset, 30.0 + offset);
        metrics.recordBatchProgress(1, 1, 2, 4, 1, 100 + offset, 1.5 + offset);
        metrics.recordBatchProgress(2, 2, 2, 4, 1, 200 + offset, 2.5 + offset);
        metrics.recordBatchComplete(1, 1, 2, 4, 1, 100 + offset, 1.5 + offset, 10.0 + offset);
        metrics.recordBatchComplete(2, 2, 2, 4, 1, 200 + offset, 2.5 + offset, 20.0 + offset);
        metrics.recordRawBodyCost(1, 1, 50 + offset);
        metrics.recordRawBodyCost(2, 2, 70 + offset);
        metrics.recordIdle(1, 1, 0, 1, 50 + offset, 10.0 + offset);
        metrics.recordIdle(2, 2, 1, 2, 150 + offset, 20.0 + offset);
        metrics.recordExec(1, 1, 2, 3, 250 + offset, 30.0 + offset);
        metrics.recordExec(2, 2, 3, 4, 350 + offset, 40.0 + offset);
        return metrics;
    }

    private static ForkCalculationResult createForkResult(int offset) {
        List<IterationResult> iterResults = new ArrayList<>(2);
        List<List<HighSpeedMetrics>> allIterMetrics = new ArrayList<>(2);
        for (int iter = 0; iter < 2; iter++) {
            List<CoreIterationResult> cores = new ArrayList<>(2);
            List<HighSpeedMetrics> metricsList = new ArrayList<>(2);
            for (int core = 0; core < 2; core++) {
                HighSpeedMetrics m = createPopulatedMetrics(offset + iter + core);
                metricsList.add(m);
                cores.add(HighSpeedMetricsStatistics.calculate(iter, core, m));
            }
            SystemIterationResult system = HighSpeedMetricsStatistics.calculateSystem(iter, metricsList);
            iterResults.add(new IterationResult(iter, system, cores));
            allIterMetrics.add(metricsList);
        }
        SystemForkResult forkSystem = HighSpeedMetricsStatistics.calculateSystemFork(0, allIterMetrics);
        return new ForkCalculationResult(forkSystem, iterResults);
    }

    private static void setupCompletedRunOnDisk(Path runDir, TrialConfig config, double throughputScore, int offset)
            throws Exception {
        Files.createDirectories(runDir);

        Files.writeString(
                runDir.resolve("trial_config.json"),
                new ObjectMapper().writeValueAsString(config),
                StandardCharsets.UTF_8);

        String logContent = "# JMH version: 1.37\n"
                + "# Benchmark: calibration.benchmarks.CalibrationBenchmark.benchmark\n"
                + "# Fork: 1 of 1\n"
                + "Iteration   1: " + throughputScore + " ops/s\n\n"
                + "Benchmark                                                 Mode  Cnt      Score     Error  Units\n"
                + "CalibrationBenchmark.benchmark                           thrpt    1  " + throughputScore
                + " +/- 1.0  ops/s\n";
        Files.writeString(runDir.resolve(Constants.BENCHMARK_OUTPUT_LOG), logContent, StandardCharsets.UTF_8);

        ForkCalculationResult forkResult = createForkResult(offset);
        TrialExport.exportAll(runDir, forkResult, false);
    }

    @Test
    void testMainNoArgumentsFailsWithUsage() {
        CalibrationRunner.MainError err =
                assertThrows(CalibrationRunner.MainError.class, () -> CalibrationRunner.main(new String[0]));
        assertTrue(err.getMessage().contains("Usage:"));
        assertTrue(err.getMessage().contains("euhedral-calibration run"));
        assertTrue(err.getMessage().contains("euhedral-calibration compare"));
    }

    @Test
    void testMainOneArgumentFailsWithUsage() {
        CalibrationRunner.MainError err =
                assertThrows(CalibrationRunner.MainError.class, () -> CalibrationRunner.main(new String[] {"run"}));
        assertTrue(err.getMessage().contains("Usage:"));
    }

    @Test
    void testMainMoreThanTwoArgumentsFailsWithUsage() {
        CalibrationRunner.MainError err = assertThrows(
                CalibrationRunner.MainError.class,
                () -> CalibrationRunner.main(new String[] {"run", "config.json", "extra"}));
        assertTrue(err.getMessage().contains("Usage:"));
    }

    @Test
    void testMainUnknownModeFailsWithUsage() {
        CalibrationRunner.MainError err = assertThrows(
                CalibrationRunner.MainError.class,
                () -> CalibrationRunner.main(new String[] {"unknown_mode", "config.json"}));
        assertTrue(err.getMessage().contains("Usage:"));
    }

    @Test
    void testRunnerModeParseCaseInsensitive() {
        assertEquals(RunnerMode.RUN, RunnerMode.parse("run"));
        assertEquals(RunnerMode.RUN, RunnerMode.parse("RUN"));
        assertEquals(RunnerMode.RUN, RunnerMode.parse("Run"));
        assertEquals(RunnerMode.COMPARE, RunnerMode.parse("compare"));
        assertEquals(RunnerMode.COMPARE, RunnerMode.parse("COMPARE"));
        assertEquals(RunnerMode.COMPARE, RunnerMode.parse("Compare"));

        assertThrows(IllegalArgumentException.class, () -> RunnerMode.parse("invalid"));
        assertThrows(IllegalArgumentException.class, () -> RunnerMode.parse(""));
        assertThrows(NullPointerException.class, () -> RunnerMode.parse(null));
    }

    @Test
    void testComparisonConfigDeserialization() throws Exception {
        String json = """
                {
                  "baseline": {
                    "path": "build/results/baseline",
                    "label": "baseline-label"
                  },
                  "candidates": [
                    {
                      "path": "build/results/candidate-a",
                      "label": "cand-a"
                    },
                    {
                      "path": "build/results/candidate-b"
                    }
                  ],
                  "options": {
                    "includeDiagnostics": true,
                    "failFast": false
                  },
                  "outputDirectory": "build/results/comparisons/test-001"
                }
                """;

        ComparisonConfig config = mapper.readValue(json, ComparisonConfig.class);
        assertNotNull(config);
        assertEquals("build/results/baseline", config.baseline().path());
        assertEquals("baseline-label", config.baseline().label());
        assertEquals(2, config.candidates().size());
        assertEquals("build/results/candidate-a", config.candidates().get(0).path());
        assertEquals("cand-a", config.candidates().get(0).label());
        assertEquals("build/results/candidate-b", config.candidates().get(1).path());
        assertNull(config.candidates().get(1).label());
        assertEquals("build/results/comparisons/test-001", config.outputDirectory());
        assertTrue(config.options().includeDiagnostics());
        assertFalse(config.options().failFast());

        ComparisonRequest request = config.toRequest();
        assertNotNull(request);
        assertEquals(config.baseline(), request.baseline());
        assertEquals(config.candidates(), request.candidates());
    }

    @Test
    void testComparisonConfigValidationRules() {
        RunReference baseline = RunReference.of("/path/to/baseline");
        RunReference cand1 = RunReference.of("/path/to/cand1");
        RunReference cand2 = RunReference.of("/path/to/cand2");

        // Null baseline rejected
        assertThrows(NullPointerException.class, () -> new ComparisonConfig(null, List.of(cand1), "outDir"));

        // Null candidates rejected
        assertThrows(NullPointerException.class, () -> new ComparisonConfig(baseline, null, "outDir"));

        // Empty candidates rejected
        assertThrows(IllegalArgumentException.class, () -> new ComparisonConfig(baseline, List.of(), "outDir"));

        // Null / blank output directory rejected
        assertThrows(NullPointerException.class, () -> new ComparisonConfig(baseline, List.of(cand1), null));
        assertThrows(IllegalArgumentException.class, () -> new ComparisonConfig(baseline, List.of(cand1), "  "));

        // Baseline appearing in candidates rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComparisonConfig(baseline, List.of(RunReference.of("/path/to/baseline")), "outDir"));

        // Duplicate candidate rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComparisonConfig(baseline, List.of(cand1, RunReference.of("/path/to/cand1")), "outDir"));
    }

    @Test
    void testRunComparisonExecutesAllCalculatorsAndExportsArtifacts(@TempDir Path tempDir) throws Exception {
        Path baseDir = tempDir.resolve("runs/base_repeat_0");
        Path candCompatDir = tempDir.resolve("runs/cand_compat_repeat_0");
        Path candPartialDir = tempDir.resolve("runs/cand_partial_repeat_0");
        Path candIncompatDir = tempDir.resolve("runs/cand_incompat_repeat_0");
        Path comparisonOutDir = tempDir.resolve("comparisons/suite_001");

        TrialConfig baseConfig = dummyTrialConfig("base", true);

        // Candidate 1: Compatible (different name only)
        TrialConfig candCompatConfig = new TrialConfig(
                "cand-compat",
                "Candidate Compatible",
                "group",
                null,
                null,
                null,
                null,
                true,
                1,
                1,
                1,
                List.of("-Xmx1g"),
                dummyCalibrationConfig());

        // Candidate 2: Partial (observeIdleDecision toggle differs)
        CalibrationBenchmarkConfig partialCalConfig = new CalibrationBenchmarkConfig(
                List.of(1, 2, 3, 4),
                4,
                2,
                100,
                true,
                1000000,
                60000,
                FragmentDecisionWeights.DEFAULT,
                1024,
                true, // observeCycleStart differs
                false,
                false,
                false,
                false,
                false);
        TrialConfig candPartialConfig = new TrialConfig(
                "cand-partial",
                "Candidate Partial",
                "group",
                null,
                null,
                null,
                null,
                true,
                1,
                1,
                1,
                List.of("-Xmx1g"),
                partialCalConfig);

        // Candidate 3: Incompatible (workUnits differs)
        CalibrationBenchmarkConfig incompatCalConfig = new CalibrationBenchmarkConfig(
                List.of(1, 2, 3, 4),
                4,
                2,
                9999, // workUnits differs
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
        TrialConfig candIncompatConfig = new TrialConfig(
                "cand-incompat",
                "Candidate Incompatible",
                "group",
                null,
                null,
                null,
                null,
                true,
                1,
                1,
                1,
                List.of("-Xmx1g"),
                incompatCalConfig);

        setupCompletedRunOnDisk(baseDir, baseConfig, 1000.0, 0);
        setupCompletedRunOnDisk(candCompatDir, candCompatConfig, 1200.0, 5);
        setupCompletedRunOnDisk(candPartialDir, candPartialConfig, 1100.0, 10);
        setupCompletedRunOnDisk(candIncompatDir, candIncompatConfig, 1500.0, 15);

        ComparisonConfig comparisonConfig = new ComparisonConfig(
                RunReference.of(baseDir.toString(), "baseline"),
                List.of(
                        RunReference.of(candCompatDir.toString(), "cand-compat"),
                        RunReference.of(candPartialDir.toString(), "cand-partial"),
                        RunReference.of(candIncompatDir.toString(), "cand-incompat")),
                ComparisonOptions.DEFAULT,
                comparisonOutDir.toString());

        Path configFile = tempDir.resolve("comparison_config.json");
        Files.writeString(configFile, mapper.writeValueAsString(comparisonConfig), StandardCharsets.UTF_8);

        // Execute comparison mode
        CalibrationComparison.runComparison(configFile.toString());

        // Verify exported comparison artifacts exist
        assertTrue(Files.exists(comparisonOutDir.resolve(Constants.COMPARISON_MANIFEST_JSON)));
        assertTrue(Files.exists(comparisonOutDir.resolve(Constants.COMPARISON_MANIFEST_CHECKSUM)));
        assertTrue(Files.exists(comparisonOutDir.resolve(Constants.COMPARISON_SUMMARY_TSV)));
        assertTrue(Files.exists(comparisonOutDir.resolve(Constants.COMPARISON_SUMMARY_CHECKSUM)));
        assertTrue(Files.exists(comparisonOutDir.resolve(Constants.CONFIGURATION_DIFFERENCES_TSV)));
        assertTrue(Files.exists(comparisonOutDir.resolve(Constants.SCALAR_COMPARISONS_TSV)));
        assertTrue(Files.exists(comparisonOutDir.resolve(Constants.OCCUPANCY_COMPARISONS_TSV)));
        assertTrue(Files.exists(comparisonOutDir.resolve(Constants.TRANSITION_COMPARISONS_TSV)));
        assertTrue(Files.exists(comparisonOutDir.resolve(Constants.VECTOR_FIELD_COMPARISONS_TSV)));
        assertTrue(Files.exists(comparisonOutDir.resolve(Constants.CORRELATION_COMPARISONS_TSV)));

        // Verify summary TSV content and order
        List<String> summaryLines =
                Files.readAllLines(comparisonOutDir.resolve(Constants.COMPARISON_SUMMARY_TSV), StandardCharsets.UTF_8);
        // Header + 3 candidate rows
        assertEquals(4, summaryLines.size());

        // Row 1: cand-compat (COMPATIBLE)
        String[] row1 = summaryLines.get(1).split("\t");
        assertEquals("base", row1[0]);
        assertEquals("cand-compat", row1[1]);
        assertEquals("COMPATIBLE", row1[2]);
        assertNotEquals("NaN", row1[3]);

        // Row 2: cand-partial (PARTIAL)
        String[] row2 = summaryLines.get(2).split("\t");
        assertEquals("base", row2[0]);
        assertEquals("cand-partial", row2[1]);
        assertEquals("PARTIAL", row2[2]);
        assertNotEquals("NaN", row2[3]);

        // Row 3: cand-incompat (INCOMPATIBLE)
        String[] row3 = summaryLines.get(3).split("\t");
        assertEquals("base", row3[0]);
        assertEquals("cand-incompat", row3[1]);
        assertEquals("INCOMPATIBLE", row3[2]);
        assertEquals("NaN", row3[3]);
        assertEquals("UNAVAILABLE", row3[16]);
    }

    @Test
    void testRunComparisonMissingConfigFileThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CalibrationComparison.runComparison("/nonexistent/path/comparison.json"));
    }

    @Test
    void testRunComparisonMissingBaselineRunThrows(@TempDir Path tempDir) throws Exception {
        ComparisonConfig config = new ComparisonConfig(
                RunReference.of("/nonexistent/baseline"),
                List.of(RunReference.of("/nonexistent/cand")),
                tempDir.resolve("out").toString());

        Path configFile = tempDir.resolve("comparison.json");
        Files.writeString(configFile, mapper.writeValueAsString(config), StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> CalibrationComparison.runComparison(configFile.toString()));
    }
}
