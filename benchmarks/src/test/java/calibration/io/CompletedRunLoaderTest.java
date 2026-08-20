package calibration.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.RunArtifacts;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ThroughputResult;
import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.TrialConfig;
import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.infra.Constants;
import calibration.io.exceptions.ChecksumMismatchException;
import calibration.io.exceptions.MalformedArtifactException;
import calibration.io.exceptions.MissingArtifactException;
import calibration.io.exceptions.MissingAuthoritativeSummaryException;
import calibration.statistics.Band;
import calibration.statistics.HighSpeedMetricsStatistics;
import calibration.statistics.fork.ForkCalculationResult;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.IterationResult;
import calibration.statistics.iteration.SystemIterationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletedRunLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TrialConfig createTrialConfig(String id, String name, String group) {
        CalibrationBenchmarkConfig calConfig = new CalibrationBenchmarkConfig(
                List.of(1, 2),
                4,
                2,
                10,
                false,
                1000L,
                5000L,
                FragmentDecisionWeights.DEFAULT,
                1024,
                true,
                true,
                true,
                true,
                true,
                true);
        return new TrialConfig(
                id,
                name,
                group,
                "Test description",
                "Test hypothesis",
                null,
                List.of("tag1"),
                null,
                true,
                null,
                1,
                1,
                3,
                "2s",
                "5s",
                List.of("-Xms2g"),
                null,
                calConfig);
    }

    private static HighSpeedMetrics createPopulatedMetrics() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(8);
        metrics.recordCycleStart(1, 1, 10, 5, 2, 4, 1, 100, 10.0);
        metrics.recordCycleStart(2, 2, 20, 5, 2, 4, 1, 200, 20.0);
        metrics.recordCycleStart(3, 3, 30, 5, 2, 4, 1, 300, 30.0);

        metrics.recordBatchProgress(1, 1, 2, 4, 1, 100, 1.5);
        metrics.recordBatchProgress(2, 2, 2, 4, 1, 200, 2.5);

        metrics.recordBatchComplete(1, 1, 2, 4, 1, 100, 1.5, 10.0);
        metrics.recordBatchComplete(2, 2, 2, 4, 1, 200, 2.5, 20.0);

        metrics.recordRawBodyCost(1, 1, 50);
        metrics.recordRawBodyCost(2, 2, 70);

        metrics.recordIdle(1, 1, 0, 1, 50, 10.0);
        metrics.recordIdle(2, 2, 1, 2, 150, 20.0);

        metrics.recordExec(1, 1, 2, 3, 250, 30.0);
        metrics.recordExec(2, 2, 3, 4, 350, 40.0);

        return metrics;
    }

    private static ForkCalculationResult createForkResult(int coreCount, int iterationCount) {
        List<IterationResult> iterResults = new ArrayList<>(iterationCount);
        List<List<HighSpeedMetrics>> allIterMetrics = new ArrayList<>(iterationCount);
        for (int iter = 0; iter < iterationCount; iter++) {
            List<CoreIterationResult> cores = new ArrayList<>(coreCount);
            List<HighSpeedMetrics> metricsList = new ArrayList<>(coreCount);
            for (int core = 0; core < coreCount; core++) {
                HighSpeedMetrics m = createPopulatedMetrics();
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

    private static void setupValidCompletedRun(Path runDir, TrialConfig config) throws Exception {
        Files.createDirectories(runDir);

        // 1. trial_config.json
        Files.writeString(
                runDir.resolve("trial_config.json"), MAPPER.writeValueAsString(config), StandardCharsets.UTF_8);

        // 2. benchmark_output.log
        String logContent = """
                # JMH version: 1.37
                # Benchmark: calibration.benchmarks.CalibrationBenchmark.benchmark
                # Fork: 1 of 1
                Iteration   1: 12345.678 ops/s
                Iteration   2: 12456.789 ops/s
                Iteration   3: 12567.890 ops/s

                Benchmark                                                 Mode  Cnt      Score     Error  Units
                CalibrationBenchmark.benchmark                           thrpt    3  12456.786 +/- 123.456  ops/s
                """;
        Files.writeString(runDir.resolve(Constants.BENCHMARK_OUTPUT_LOG), logContent, StandardCharsets.UTF_8);

        // 3. TSV exports with checksums
        ForkCalculationResult forkResult = createForkResult(2, 3);
        TrialExport.exportAll(runDir, forkResult, false);
    }

    @Test
    void testValidCompletedRunLoadsSuccessfully(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("trial_1_repeat_0");
        TrialConfig config = createTrialConfig("trial_1", "Trial One", "group_a");
        setupValidCompletedRun(runDir, config);

        CompletedRun completedRun = CompletedRunLoader.load(runDir);
        assertNotNull(completedRun);

        // 1. Identity
        RunIdentity id = completedRun.identity();
        assertEquals("trial_1", id.trialId());
        assertEquals("Trial One", id.trialName());
        assertEquals("group_a", id.trialGroup());
        assertEquals(0, id.repeatIndex());
        assertEquals(runDir.toAbsolutePath().normalize().toString(), id.sourcePath());

        // 2. Config
        assertEquals(config, completedRun.trialConfig());

        // 3. Throughput
        ThroughputResult throughput = completedRun.throughput();
        assertEquals(12456.786, throughput.score());
        assertEquals(123.456, throughput.scoreError());
        assertEquals("ops/s", throughput.scoreUnit());
        assertEquals(3, throughput.iterationScores().size());

        // 4. SystemForkResult
        SystemForkResult system = completedRun.system();
        assertNotNull(system);
        assertTrue(system.cycleStartTotal() > 0L);
        assertTrue(system.batchProgressTotal() > 0L);
        assertTrue(system.batchCompleteTotal() > 0L);
        assertTrue(system.rawBodyCostTotal() > 0L);
        assertTrue(system.idleDecisionTotal() > 0L);
        assertTrue(system.execDecisionTotal() > 0L);
        assertNotNull(system.cycleStart());
        assertNotNull(system.batchProgress());
        assertNotNull(system.batchComplete());
        assertNotNull(system.rawBodyCost());
        assertNotNull(system.idleDecisions());
        assertNotNull(system.execDecisions());

        // 5. Artifacts
        RunArtifacts artifacts = completedRun.artifacts();
        assertNotNull(artifacts.trialConfigPath());
        assertNotNull(artifacts.rawObservationsPath());
        assertNotNull(artifacts.rawObservationsChecksumPath());
        assertNotNull(artifacts.statisticsPath());
        assertNotNull(artifacts.statisticsChecksumPath());
        assertNotNull(artifacts.occupancyPath());
        assertNotNull(artifacts.transitionsPath());
        assertNotNull(artifacts.vectorFieldsPath());
        assertNotNull(artifacts.correlationsPath());
        assertNotNull(artifacts.benchmarkOutputPath());
    }

    @Test
    void testResolvedTrialConfigReconstructedCorrectly(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("trial_custom_repeat_2");
        TrialConfig config = createTrialConfig("trial_custom", "Custom Run", "group_b");
        setupValidCompletedRun(runDir, config);

        CompletedRun completedRun = CompletedRunLoader.load(runDir);
        assertEquals(config.forks(), completedRun.trialConfig().forks());
        assertEquals(config.warmups(), completedRun.trialConfig().warmups());
        assertEquals(config.iterations(), completedRun.trialConfig().iterations());
        assertEquals(
                config.calibrationConfig().workUnits(),
                completedRun.trialConfig().calibrationConfig().workUnits());
        assertEquals(
                config.calibrationConfig().parallelSources(),
                completedRun.trialConfig().calibrationConfig().parallelSources());
    }

    @Test
    void testThroughputResultReconstructedCorrectly(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("t1_repeat_0");
        TrialConfig config = createTrialConfig("t1", "T1", "g");
        setupValidCompletedRun(runDir, config);

        CompletedRun run = CompletedRunLoader.load(runDir);
        ThroughputResult tp = run.throughput();
        assertEquals(12456.786, tp.score(), 1e-6);
        assertEquals(123.456, tp.scoreError(), 1e-6);
        assertEquals("ops/s", tp.scoreUnit());
        assertEquals(List.of(12345.678, 12456.789, 12567.890), tp.iterationScores());
    }

    @Test
    void testLoadsRetainedForksWithIndependentThroughput(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("multifork_repeat_2");
        Files.createDirectories(runDir);
        TrialConfig config = createTrialConfig("multifork", "Multi Fork", "phase11");
        Files.writeString(
                runDir.resolve("trial_config.json"), MAPPER.writeValueAsString(config), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve(Constants.BENCHMARK_OUTPUT_LOG), """
                # Fork: 1 of 2
                Iteration   1: 1.0 ops/s
                                 executions: 1000.0 ops/s
                Iteration   2: 1.0 ops/s
                                 executions: 1200.0 ops/s
                # Fork: 2 of 2
                Iteration   1: 1.0 ops/s
                                 executions: 1400.0 ops/s
                Iteration   2: 1.0 ops/s
                                 executions: 1600.0 ops/s
                Secondary result "calibration.CalibrationBenchmark.calibrate:executions":
                  1300.0 +/- 100.0 ops/s [Average]
                """, StandardCharsets.UTF_8);

        Path firstFork = runDir.resolve("fork-100-200");
        Path secondFork = runDir.resolve("fork-300-400");
        TrialExport.exportAll(firstFork, createForkResult(2, 2), false);
        TrialExport.exportAll(secondFork, createForkResult(2, 2), false);

        List<CompletedRun> forks = CompletedRunLoader.loadForks(runDir.toString());

        assertEquals(2, forks.size());
        assertEquals(0, forks.get(0).identity().forkIndex());
        assertEquals(1, forks.get(1).identity().forkIndex());
        assertEquals(2, forks.get(0).identity().repeatIndex());
        assertEquals(1100.0, forks.get(0).throughput().score(), 1e-6);
        assertEquals(1500.0, forks.get(1).throughput().score(), 1e-6);
        assertEquals(List.of(1400.0, 1600.0), forks.get(1).throughput().iterationScores());
    }

    @Test
    void testSystemForkResultReconstructedCorrectly(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("t1_repeat_0");
        TrialConfig config = createTrialConfig("t1", "T1", "g");
        setupValidCompletedRun(runDir, config);

        CompletedRun run = CompletedRunLoader.load(runDir);
        SystemForkResult sys = run.system();
        assertEquals(Band.GRID_SIZE, sys.idleOccupancy().exactCounts().length);
        assertEquals(Band.GRID_SIZE, sys.execOccupancy().exactCounts().length);
        assertEquals(Band.TOTAL_STATES, sys.idleHeadTransitions().transitionCounts().length);
        assertEquals(Band.GRID_SIZE, sys.idleHeadVectorField().grid().length);
        assertNotNull(sys.cycleStart().headCorrelations().pearsonMatrix());
        assertEquals(
                2.0, sys.batchComplete().steadyState().productiveHandleCount().mean());
        assertEquals(
                0.5, sys.batchComplete().steadyState().productiveHandleRatio().mean());
    }

    @Test
    void testRunIdentityContainsMetadata(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("experiment_sweep_repeat_3");
        TrialConfig config = createTrialConfig("experiment_sweep", "Experiment Name", "sweep_group");
        setupValidCompletedRun(runDir, config);

        CompletedRun run = CompletedRunLoader.load(runDir);
        RunIdentity id = run.identity();
        assertEquals("experiment_sweep", id.trialId());
        assertEquals("Experiment Name", id.trialName());
        assertEquals("sweep_group", id.trialGroup());
        assertEquals(3, id.repeatIndex());
    }

    @Test
    void testRequiredMissingArtifactFails(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("run_missing_artifact");
        TrialConfig config = createTrialConfig("m1", "M1", "g");
        setupValidCompletedRun(runDir, config);

        // Remove trial_config.json
        Files.delete(runDir.resolve("trial_config.json"));
        assertThrows(MissingArtifactException.class, () -> CompletedRunLoader.load(runDir));

        // Restore trial_config.json, remove benchmark_output.log
        Files.writeString(runDir.resolve("trial_config.json"), MAPPER.writeValueAsString(config));
        Files.delete(runDir.resolve(Constants.BENCHMARK_OUTPUT_LOG));
        assertThrows(MissingArtifactException.class, () -> CompletedRunLoader.load(runDir));

        // Restore log, remove raw_observations.tsv
        Files.writeString(
                runDir.resolve(Constants.BENCHMARK_OUTPUT_LOG),
                "Iteration 1: 100 ops/s\nBenchmark Mode Cnt Score Error Units\nB thrpt 1 100 ops/s");
        Files.delete(runDir.resolve(Constants.RAW_OBSERVATION_TSV));
        assertThrows(MissingArtifactException.class, () -> CompletedRunLoader.load(runDir));
    }

    @Test
    void testOptionalDiagnosticArtifactMayBeAbsent(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("run_no_checksums");
        TrialConfig config = createTrialConfig("m2", "M2", "g");
        setupValidCompletedRun(runDir, config);

        // Delete checksum files
        Files.deleteIfExists(runDir.resolve(Constants.RAW_OBSERVATION_CHECKSUM));
        Files.deleteIfExists(runDir.resolve(Constants.STATISTICS_CHECKSUM));
        Files.deleteIfExists(runDir.resolve(Constants.OCCUPANCY_CHECKSUM));
        Files.deleteIfExists(runDir.resolve(Constants.TRANSITIONS_CHECKSUM));
        Files.deleteIfExists(runDir.resolve(Constants.VECTOR_FIELDS_CHECKSUM));
        Files.deleteIfExists(runDir.resolve(Constants.CORRELATIONS_CHECKSUM));

        CompletedRun run = CompletedRunLoader.load(runDir);
        assertNotNull(run);
        assertNull(run.artifacts().rawObservationsChecksumPath());
    }

    @Test
    void testChecksumMismatchFails(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("run_bad_checksum");
        TrialConfig config = createTrialConfig("m3", "M3", "g");
        setupValidCompletedRun(runDir, config);

        // Corrupt checksum file
        Files.writeString(
                runDir.resolve(Constants.RAW_OBSERVATION_CHECKSUM),
                "0000000000000000000000000000000000000000000000000000000000000000\n",
                StandardCharsets.UTF_8);

        ChecksumMismatchException ex =
                assertThrows(ChecksumMismatchException.class, () -> CompletedRunLoader.load(runDir));
        assertEquals(runDir.resolve(Constants.RAW_OBSERVATION_TSV), ex.artifactPath());
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", ex.expectedChecksum());
        assertNotNull(ex.actualChecksum());
    }

    @Test
    void testInvalidTrialConfigJsonFails(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("run_invalid_json");
        TrialConfig config = createTrialConfig("m4", "M4", "g");
        setupValidCompletedRun(runDir, config);

        // Corrupt trial_config.json
        Files.writeString(runDir.resolve("trial_config.json"), "{ invalid json: ...", StandardCharsets.UTF_8);

        assertThrows(MalformedArtifactException.class, () -> CompletedRunLoader.load(runDir));
    }

    @Test
    void testInvalidJmhThroughputArtifactFails(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("run_invalid_jmh");
        TrialConfig config = createTrialConfig("m5", "M5", "g");
        setupValidCompletedRun(runDir, config);

        // Write non-JMH content in benchmark_output.log
        Files.writeString(
                runDir.resolve(Constants.BENCHMARK_OUTPUT_LOG),
                "Random garbage error logs without any throughput numbers\nFatal crash occurred",
                StandardCharsets.UTF_8);

        assertThrows(MalformedArtifactException.class, () -> CompletedRunLoader.load(runDir));
    }

    @Test
    void testMissingForkLevelSystemSummaryFails(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("run_missing_fork_summary");
        TrialConfig config = createTrialConfig("m6", "M6", "g");
        setupValidCompletedRun(runDir, config);

        // Write raw_observations.tsv without FORK scope row
        String header = SystemForkResult.TSV_HEADER;
        String onlyCoreRow = "0\tCORE\t0\t10\t10\t10\t10\t10\t10\t0.5\n";
        Files.writeString(runDir.resolve(Constants.RAW_OBSERVATION_TSV), header + onlyCoreRow, StandardCharsets.UTF_8);
        TrialExport.writeChecksum(runDir.resolve(Constants.RAW_OBSERVATION_TSV));

        assertThrows(MissingAuthoritativeSummaryException.class, () -> CompletedRunLoader.load(runDir));
    }

    @Test
    void testMultipleConflictingForkSummariesFail(@TempDir Path tempDir) throws Exception {
        Path runDir = tempDir.resolve("run_multiple_fork_summaries");
        TrialConfig config = createTrialConfig("m7", "M7", "g");
        setupValidCompletedRun(runDir, config);

        // Write raw_observations.tsv with 2 FORK scope rows
        String header = SystemForkResult.TSV_HEADER;
        String forkRow1 = "-1\tFORK\t-1\t10\t10\t10\t10\t10\t10\t0.5\n";
        String forkRow2 = "-1\tFORK\t-1\t20\t20\t20\t20\t20\t20\t0.6\n";
        Files.writeString(
                runDir.resolve(Constants.RAW_OBSERVATION_TSV), header + forkRow1 + forkRow2, StandardCharsets.UTF_8);
        TrialExport.writeChecksum(runDir.resolve(Constants.RAW_OBSERVATION_TSV));

        assertThrows(MalformedArtifactException.class, () -> CompletedRunLoader.load(runDir));
    }
}
