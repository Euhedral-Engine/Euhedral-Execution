package calibration.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.infra.Constants;
import calibration.statistics.HighSpeedMetricsStatistics;
import calibration.statistics.fork.ForkCalculationResult;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.IterationResult;
import calibration.statistics.iteration.SystemIterationResult;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Unit tests for CalibrationBenchmark TSV exports and SHA-256 checksum generation.
class TrialExportTest {

    private static String computeSha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static HighSpeedMetrics createPopulatedMetrics() {
        HighSpeedMetrics metrics = new HighSpeedMetrics(8);

        // Cycle start samples
        metrics.recordCycleStart(1, 1, 10, 5, 2, 4, 1, 100, 10.0);
        metrics.recordCycleStart(2, 2, 20, 5, 2, 4, 1, 200, 20.0);
        metrics.recordCycleStart(3, 3, 30, 5, 2, 4, 1, 300, 30.0);

        // Batch progress samples
        metrics.recordBatchProgress(1, 1, 2, 4, 1, 100, 1.5);
        metrics.recordBatchProgress(2, 2, 2, 4, 1, 200, 2.5);

        // Batch complete samples
        metrics.recordBatchComplete(1, 1, 2, 4, 1, 100, 1.5, 10.0);
        metrics.recordBatchComplete(2, 2, 2, 4, 1, 200, 2.5, 20.0);

        // Raw body cost
        metrics.recordRawBodyCost(1, 1, 50);
        metrics.recordRawBodyCost(2, 2, 70);

        // Idle decisions (contentionPolicy=0, bodyPolicy=1 -> state 1) -> (1, 2 -> state 7)
        metrics.recordIdle(1, 1, 0, 1, 50, 10.0);
        metrics.recordIdle(2, 2, 1, 2, 150, 20.0);

        // Exec decisions (contentionPolicy=2, bodyPolicy=3 -> state 13) -> (3, 4 -> state 19)
        metrics.recordExec(1, 1, 2, 3, 250, 30.0);
        metrics.recordExec(2, 2, 3, 4, 350, 40.0);

        return metrics;
    }

    private static IterationResult createPopulatedIteration(int iteration, int coreCount) {
        List<CoreIterationResult> cores = new ArrayList<>(coreCount);
        List<HighSpeedMetrics> metricsList = new ArrayList<>(coreCount);
        for (int core = 0; core < coreCount; core++) {
            HighSpeedMetrics m = createPopulatedMetrics();
            metricsList.add(m);
            cores.add(HighSpeedMetricsStatistics.calculate(iteration, core, m));
        }
        SystemIterationResult system = HighSpeedMetricsStatistics.calculateSystem(iteration, metricsList);
        return new IterationResult(iteration, system, cores);
    }

    private static ForkCalculationResult createPopulatedFork(int coreCount, int iterationCount) {
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

    @Test
    void testConstantsMatchExpectedFileNames() {
        assertEquals("raw_observations.tsv", Constants.RAW_OBSERVATION_TSV);
        assertEquals("raw_observations.tsv", Constants.RAW_OBSERVATIONS_TSV);
        assertEquals("raw_observations.tsv.sha256", Constants.RAW_OBSERVATION_CHECKSUM);
        assertEquals("raw_observations.tsv.sha256", Constants.RAW_OBSERVATIONS_CHECKSUM);

        assertEquals("statistics.tsv", Constants.STATISTICS_TSV);
        assertEquals("statistics.tsv.sha256", Constants.STATISTICS_CHECKSUM);

        assertEquals("occupancy.tsv", Constants.OCCUPANCY_TSV);
        assertEquals("occupancy.tsv.sha256", Constants.OCCUPANCY_CHECKSUM);

        assertEquals("transitions.tsv", Constants.TRANSITIONS_TSV);
        assertEquals("transitions.tsv.sha256", Constants.TRANSITIONS_CHECKSUM);

        assertEquals("vector_fields.tsv", Constants.VECTOR_FIELDS_TSV);
        assertEquals("vector_fields.tsv.sha256", Constants.VECTOR_FIELDS_CHECKSUM);

        assertEquals("correlations.tsv", Constants.CORRELATIONS_TSV);
        assertEquals("correlations.tsv.sha256", Constants.CORRELATIONS_CHECKSUM);

        assertEquals("euhedral.calibration.retainObserverData", Constants.RETAIN_OBSERVER_DATA_PROP);
        assertEquals("euhedral.calibration.retainObserverData", Constants.RETAIN_OBSERVER_PROP);
        assertEquals("euhedral.calibration.retainPerForkResults", Constants.RETAIN_PER_FORK_RESULTS_PROP);
        assertEquals("euhedral.calibration.retainPerForkResults", Constants.RETAIN_PER_FORK_PROP);
        assertEquals("euhedral.calibration.retainPerIterationResults", Constants.RETAIN_PER_ITERATION_RESULTS_PROP);
        assertEquals("euhedral.calibration.retainPerIterationResults", Constants.RETAIN_PER_ITERATION_PROP);
    }

    @Test
    void testExportRawObservationsTsvEmptyResultsPerformsNoWrites(@TempDir Path tempDir) throws Exception {
        TrialExport.exportRawObservationsTsv(tempDir, (ForkCalculationResult) null);
        TrialExport.exportRawObservationsTsv(tempDir, (List<IterationResult>) null);
        TrialExport.exportRawObservationsTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.RAW_OBSERVATION_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.RAW_OBSERVATION_CHECKSUM)));

        TrialExport.exportRawObservationsTsv(null, (ForkCalculationResult) null);
        TrialExport.exportRawObservationsTsv(null, List.of());
    }

    @Test
    void testExportRawObservationsTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        ForkCalculationResult forkResult = createPopulatedFork(2, 2); // 1 FORK + 2 ITERATION + 4 CORE = 7 rows

        TrialExport.exportRawObservationsTsv(tempDir, forkResult);

        Path tsv = tempDir.resolve(Constants.RAW_OBSERVATION_TSV);
        Path checksum = tempDir.resolve(Constants.RAW_OBSERVATION_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        // Header (1) + FORK (1) + Iter 0 (1 system + 2 cores) + Iter 1 (1 system + 2 cores) = 1 + 1 + 3 + 3 = 8 lines
        assertEquals(8, lines.size());
        assertEquals(
                "iteration\tscope\tcore\tcycleStartTotal\tbatchProgressTotal\tbatchCompleteTotal\trawBodyCostTotal\tidleDecisionTotal\texecDecisionTotal\tcentroidDistance",
                lines.get(0));

        // Row 1: FORK
        String[] forkRow = lines.get(1).split("\t");
        assertEquals("-1", forkRow[0]);
        assertEquals("FORK", forkRow[1]);
        assertEquals("-1", forkRow[2]);
        assertEquals(String.valueOf(forkResult.system().cycleStartTotal()), forkRow[3]);

        // Row 2: Iter 0 ITERATION
        String[] row0Sys = lines.get(2).split("\t");
        assertEquals("0", row0Sys[0]);
        assertEquals("ITERATION", row0Sys[1]);
        assertEquals("-1", row0Sys[2]);
        assertEquals(String.valueOf(forkResult.iterations().get(0).system().cycleStartTotal()), row0Sys[3]);

        // Row 3: Iter 0 CORE 0
        String[] row0Core0 = lines.get(3).split("\t");
        assertEquals("0", row0Core0[0]);
        assertEquals("CORE", row0Core0[1]);
        assertEquals("0", row0Core0[2]);

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportStatisticsTsvEmptyResultsPerformsNoWrites(@TempDir Path tempDir) throws Exception {
        TrialExport.exportStatisticsTsv(tempDir, (ForkCalculationResult) null);
        TrialExport.exportStatisticsTsv(tempDir, (List<IterationResult>) null);
        TrialExport.exportStatisticsTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.STATISTICS_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.STATISTICS_CHECKSUM)));

        TrialExport.exportStatisticsTsv(null, (ForkCalculationResult) null);
        TrialExport.exportStatisticsTsv(null, List.of());
    }

    @Test
    void testExportStatisticsTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        ForkCalculationResult forkResult = createPopulatedFork(1, 1);

        TrialExport.exportStatisticsTsv(tempDir, forkResult);

        Path tsv = tempDir.resolve(Constants.STATISTICS_TSV);
        Path checksum = tempDir.resolve(Constants.STATISTICS_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        assertTrue(lines.size() > 1);
        assertEquals(
                "iteration\tscope\tcore\tmetric\tsegment\tvariable\tcount\tmean\tstdDev\tvariance\tcv\tmin\tmax\tmedian\tp25\tp50\tp75\tp95\tiqr\tnormalizedIqr\tp95ToP50Ratio",
                lines.get(0));

        // Verify cycleStart throughput lines for FORK, ITERATION, and CORE scopes
        boolean foundFork = false;
        boolean foundIteration = false;
        boolean foundCore = false;
        for (String line : lines) {
            String[] tokens = line.split("\t");
            if (tokens.length >= 6
                    && tokens[0].equals("-1")
                    && tokens[1].equals("FORK")
                    && tokens[2].equals("-1")
                    && tokens[3].equals("cycleStart")
                    && tokens[4].equals("head")
                    && tokens[5].equals("throughput")) {
                foundFork = true;
                assertEquals(
                        String.valueOf(forkResult
                                .system()
                                .cycleStart()
                                .head()
                                .throughput()
                                .count()),
                        tokens[6]);
            }
            if (tokens.length >= 6
                    && tokens[0].equals("0")
                    && tokens[1].equals("ITERATION")
                    && tokens[2].equals("-1")
                    && tokens[3].equals("cycleStart")
                    && tokens[4].equals("head")
                    && tokens[5].equals("throughput")) {
                foundIteration = true;
                assertEquals(
                        String.valueOf(forkResult
                                .iterations()
                                .get(0)
                                .system()
                                .cycleStart()
                                .head()
                                .throughput()
                                .count()),
                        tokens[6]);
            }
            if (tokens.length >= 6
                    && tokens[0].equals("0")
                    && tokens[1].equals("CORE")
                    && tokens[2].equals("0")
                    && tokens[3].equals("cycleStart")
                    && tokens[4].equals("head")
                    && tokens[5].equals("throughput")) {
                foundCore = true;
                assertEquals(
                        String.valueOf(forkResult
                                .iterations()
                                .get(0)
                                .cores()
                                .get(0)
                                .cycleStart()
                                .head()
                                .throughput()
                                .count()),
                        tokens[6]);
            }
        }
        assertTrue(foundFork);
        assertTrue(foundIteration);
        assertTrue(foundCore);

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportOccupancyTsvEmptyResultsPerformsNoWrites(@TempDir Path tempDir) throws Exception {
        TrialExport.exportOccupancyTsv(tempDir, (ForkCalculationResult) null);
        TrialExport.exportOccupancyTsv(tempDir, (List<IterationResult>) null);
        TrialExport.exportOccupancyTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.OCCUPANCY_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.OCCUPANCY_CHECKSUM)));

        TrialExport.exportOccupancyTsv(null, (ForkCalculationResult) null);
        TrialExport.exportOccupancyTsv(null, List.of());
    }

    @Test
    void testExportOccupancyTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        ForkCalculationResult forkResult = createPopulatedFork(1, 1);

        TrialExport.exportOccupancyTsv(tempDir, forkResult);

        Path tsv = tempDir.resolve(Constants.OCCUPANCY_TSV);
        Path checksum = tempDir.resolve(Constants.OCCUPANCY_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        // 1 header + 50 (FORK idle+exec) + 50 (ITERATION idle+exec) + 50 (CORE 0 idle+exec) = 151 lines
        assertEquals(151, lines.size());
        assertEquals(
                "iteration\tscope\tcore\tdecisionType\tcontentionBand\tbodyBand\tcount\tprobability\tcontentionCentroid\tbodyCentroid\tcontentionVariance\tbodyVariance\tcontentionBodyCovariance\tradiusSquared\tradius",
                lines.get(0));

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportTransitionsTsvEmptyResultsPerformsNoWrites(@TempDir Path tempDir) throws Exception {
        TrialExport.exportTransitionsTsv(tempDir, (ForkCalculationResult) null);
        TrialExport.exportTransitionsTsv(tempDir, (List<IterationResult>) null);
        TrialExport.exportTransitionsTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.TRANSITIONS_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.TRANSITIONS_CHECKSUM)));

        TrialExport.exportTransitionsTsv(null, (ForkCalculationResult) null);
        TrialExport.exportTransitionsTsv(null, List.of());
    }

    @Test
    void testExportTransitionsTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        ForkCalculationResult forkResult = createPopulatedFork(1, 1);

        TrialExport.exportTransitionsTsv(tempDir, forkResult);

        Path tsv = tempDir.resolve(Constants.TRANSITIONS_TSV);
        Path checksum = tempDir.resolve(Constants.TRANSITIONS_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        // 1 header + 3 (FORK + ITERATION + CORE) * 2 (decisionTypes) * 2 (segments) * 25 * 25 = 1 + 3 * 2500 = 7501
        // lines
        assertEquals(7501, lines.size());
        assertEquals(
                "iteration\tscope\tcore\tdecisionType\tsegment\tfromState\tfromContention\tfromBody\ttoState\ttoContention\ttoBody\tcount\tprobability\tselfTransitionRate\tdominantOutgoingState\tdominantOutgoingProbability",
                lines.get(0));

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportVectorFieldsTsvEmptyResultsPerformsNoWrites(@TempDir Path tempDir) throws Exception {
        TrialExport.exportVectorFieldsTsv(tempDir, (ForkCalculationResult) null);
        TrialExport.exportVectorFieldsTsv(tempDir, (List<IterationResult>) null);
        TrialExport.exportVectorFieldsTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.VECTOR_FIELDS_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.VECTOR_FIELDS_CHECKSUM)));

        TrialExport.exportVectorFieldsTsv(null, (ForkCalculationResult) null);
        TrialExport.exportVectorFieldsTsv(null, List.of());
    }

    @Test
    void testExportVectorFieldsTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        ForkCalculationResult forkResult = createPopulatedFork(1, 1);

        TrialExport.exportVectorFieldsTsv(tempDir, forkResult);

        Path tsv = tempDir.resolve(Constants.VECTOR_FIELDS_TSV);
        Path checksum = tempDir.resolve(Constants.VECTOR_FIELDS_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        // 1 header + 3 (FORK + ITERATION + CORE) * 2 (decisionTypes) * 2 (segments) * 25 (cells) = 1 + 3 * 100 = 301
        // lines
        assertEquals(301, lines.size());
        assertEquals(
                "iteration\tscope\tcore\tdecisionType\tsegment\tcontentionBand\tbodyBand\ttransitionCount\tmeanDeltaContention\tmeanDeltaBody\tmagnitude",
                lines.get(0));

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportCorrelationsTsvEmptyResultsPerformsNoWrites(@TempDir Path tempDir) throws Exception {
        TrialExport.exportCorrelationsTsv(tempDir, (ForkCalculationResult) null);
        TrialExport.exportCorrelationsTsv(tempDir, (List<IterationResult>) null);
        TrialExport.exportCorrelationsTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.CORRELATIONS_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.CORRELATIONS_CHECKSUM)));

        TrialExport.exportCorrelationsTsv(null, (ForkCalculationResult) null);
        TrialExport.exportCorrelationsTsv(null, List.of());
    }

    @Test
    void testExportCorrelationsTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        ForkCalculationResult forkResult = createPopulatedFork(1, 1);

        TrialExport.exportCorrelationsTsv(tempDir, forkResult);

        Path tsv = tempDir.resolve(Constants.CORRELATIONS_TSV);
        Path checksum = tempDir.resolve(Constants.CORRELATIONS_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        // 1 header + 3 (FORK + ITERATION + CORE) * 3 segments * (7*7 + 2*2 + 3*3 + 3*3 + 3*3) = 1 + 3 * 3 * 80 = 721
        // lines
        assertEquals(721, lines.size());
        assertEquals("iteration\tscope\tcore\tmetric\tsegment\tvariable1\tvariable2\tpearson\tspearman", lines.get(0));

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportAllWithForkCalculationResultPerIterationFalse(@TempDir Path tempDir) throws Exception {
        ForkCalculationResult forkResult = createPopulatedFork(1, 2);

        TrialExport.exportAll(tempDir, forkResult, false);

        assertTrue(Files.exists(tempDir.resolve(Constants.RAW_OBSERVATION_TSV)));
        assertTrue(Files.exists(tempDir.resolve(Constants.STATISTICS_TSV)));
        assertTrue(Files.exists(tempDir.resolve(Constants.OCCUPANCY_TSV)));
        assertTrue(Files.exists(tempDir.resolve(Constants.TRANSITIONS_TSV)));
        assertTrue(Files.exists(tempDir.resolve(Constants.VECTOR_FIELDS_TSV)));
        assertTrue(Files.exists(tempDir.resolve(Constants.CORRELATIONS_TSV)));

        assertFalse(Files.exists(tempDir.resolve("iteration-0")));
        assertFalse(Files.exists(tempDir.resolve("iteration-1")));
    }

    @Test
    void testExportAllWithForkCalculationResultPerIterationTrue(@TempDir Path tempDir) throws Exception {
        ForkCalculationResult forkResult = createPopulatedFork(1, 2);

        TrialExport.exportAll(tempDir, forkResult, true);

        assertTrue(Files.exists(tempDir.resolve(Constants.RAW_OBSERVATION_TSV)));
        assertTrue(Files.exists(tempDir.resolve(Constants.STATISTICS_TSV)));
        assertTrue(Files.exists(tempDir.resolve(Constants.OCCUPANCY_TSV)));
        assertTrue(Files.exists(tempDir.resolve(Constants.TRANSITIONS_TSV)));
        assertTrue(Files.exists(tempDir.resolve(Constants.VECTOR_FIELDS_TSV)));
        assertTrue(Files.exists(tempDir.resolve(Constants.CORRELATIONS_TSV)));

        Path iter0 = tempDir.resolve("iteration-0");
        assertTrue(Files.exists(iter0));
        assertTrue(Files.exists(iter0.resolve(Constants.RAW_OBSERVATION_TSV)));
        assertTrue(Files.exists(iter0.resolve(Constants.STATISTICS_TSV)));
        assertTrue(Files.exists(iter0.resolve(Constants.OCCUPANCY_TSV)));
        assertTrue(Files.exists(iter0.resolve(Constants.TRANSITIONS_TSV)));
        assertTrue(Files.exists(iter0.resolve(Constants.VECTOR_FIELDS_TSV)));
        assertTrue(Files.exists(iter0.resolve(Constants.CORRELATIONS_TSV)));

        Path iter1 = tempDir.resolve("iteration-1");
        assertTrue(Files.exists(iter1));
        assertTrue(Files.exists(iter1.resolve(Constants.RAW_OBSERVATION_TSV)));
        assertTrue(Files.exists(iter1.resolve(Constants.STATISTICS_TSV)));
        assertTrue(Files.exists(iter1.resolve(Constants.OCCUPANCY_TSV)));
        assertTrue(Files.exists(iter1.resolve(Constants.TRANSITIONS_TSV)));
        assertTrue(Files.exists(iter1.resolve(Constants.VECTOR_FIELDS_TSV)));
        assertTrue(Files.exists(iter1.resolve(Constants.CORRELATIONS_TSV)));
    }
}
