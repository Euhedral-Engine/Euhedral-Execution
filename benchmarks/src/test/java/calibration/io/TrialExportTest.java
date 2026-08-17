package calibration.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.infra.Constants;
import calibration.statistics.HighSpeedMetricsStatistics;
import calibration.statistics.iteration.CoreIterationResult;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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

    private static CoreIterationResult createPopulatedResult(int iteration, int core) {
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

        return HighSpeedMetricsStatistics.calculate(iteration, core, metrics);
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
        TrialExport.exportRawObservationsTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.RAW_OBSERVATION_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.RAW_OBSERVATION_CHECKSUM)));

        TrialExport.exportRawObservationsTsv(null, List.of());
        TrialExport.exportRawObservationsTsv(tempDir, null);
    }

    @Test
    void testExportRawObservationsTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        CoreIterationResult r0c0 = createPopulatedResult(0, 0);
        CoreIterationResult r0c1 = createPopulatedResult(0, 1);
        CoreIterationResult r1c0 = createPopulatedResult(1, 0);
        List<List<CoreIterationResult>> results = List.of(List.of(r0c0, r0c1), List.of(r1c0));

        TrialExport.exportRawObservationsTsv(tempDir, results);

        Path tsv = tempDir.resolve(Constants.RAW_OBSERVATION_TSV);
        Path checksum = tempDir.resolve(Constants.RAW_OBSERVATION_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        assertEquals(4, lines.size()); // header + 3 data rows
        assertEquals(
                "iteration\tcore\tcycleStartTotal\tbatchProgressTotal\tbatchCompleteTotal\trawBodyCostTotal\tidleDecisionTotal\texecDecisionTotal\tcentroidDistance",
                lines.get(0));

        String[] row0 = lines.get(1).split("\t");
        assertEquals("0", row0[0]); // iteration
        assertEquals("0", row0[1]); // core
        assertEquals(String.valueOf(r0c0.cycleStartTotal()), row0[2]);
        assertEquals(String.valueOf(r0c0.batchProgressTotal()), row0[3]);
        assertEquals(String.valueOf(r0c0.batchCompleteTotal()), row0[4]);
        assertEquals(String.valueOf(r0c0.rawBodyCostTotal()), row0[5]);
        assertEquals(String.valueOf(r0c0.idleDecisionTotal()), row0[6]);
        assertEquals(String.valueOf(r0c0.execDecisionTotal()), row0[7]);
        assertEquals(String.valueOf(r0c0.centroidDistance()), row0[8]);

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportStatisticsTsvEmptyResultsPerformsNoWrites(@TempDir Path tempDir) throws Exception {
        TrialExport.exportStatisticsTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.STATISTICS_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.STATISTICS_CHECKSUM)));

        TrialExport.exportStatisticsTsv(null, List.of());
        TrialExport.exportStatisticsTsv(tempDir, null);
    }

    @Test
    void testExportStatisticsTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        CoreIterationResult r0c0 = createPopulatedResult(0, 0);
        List<List<CoreIterationResult>> results = List.of(List.of(r0c0));

        TrialExport.exportStatisticsTsv(tempDir, results);

        Path tsv = tempDir.resolve(Constants.STATISTICS_TSV);
        Path checksum = tempDir.resolve(Constants.STATISTICS_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        assertTrue(lines.size() > 1);
        assertEquals(
                "iteration\tcore\tmetric\tsegment\tvariable\tcount\tmean\tstdDev\tvariance\tcv\tmin\tmax\tmedian\tp25\tp50\tp75\tp95\tiqr\tnormalizedIqr\tp95ToP50Ratio",
                lines.get(0));

        // Verify cycleStart throughput line
        boolean foundCycleStartThroughput = false;
        for (String line : lines) {
            String[] tokens = line.split("\t");
            if (tokens.length >= 5
                    && tokens[0].equals("0")
                    && tokens[1].equals("0")
                    && tokens[2].equals("cycleStart")
                    && tokens[3].equals("head")
                    && tokens[4].equals("throughput")) {
                foundCycleStartThroughput = true;
                assertEquals(
                        String.valueOf(r0c0.cycleStart().head().throughput().count()), tokens[5]);
                assertEquals(
                        String.valueOf(r0c0.cycleStart().head().throughput().mean()), tokens[6]);
            }
        }
        assertTrue(foundCycleStartThroughput);

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportOccupancyTsvEmptyResultsPerformsNoWrites(@TempDir Path tempDir) throws Exception {
        TrialExport.exportOccupancyTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.OCCUPANCY_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.OCCUPANCY_CHECKSUM)));

        TrialExport.exportOccupancyTsv(null, List.of());
        TrialExport.exportOccupancyTsv(tempDir, null);
    }

    @Test
    void testExportOccupancyTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        CoreIterationResult r0c0 = createPopulatedResult(0, 0);
        List<List<CoreIterationResult>> results = List.of(List.of(r0c0));

        TrialExport.exportOccupancyTsv(tempDir, results);

        Path tsv = tempDir.resolve(Constants.OCCUPANCY_TSV);
        Path checksum = tempDir.resolve(Constants.OCCUPANCY_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        // 1 header + 25 (idle) + 25 (exec) = 51 lines
        assertEquals(51, lines.size());
        assertEquals(
                "iteration\tcore\tdecisionType\tcontentionBand\tbodyBand\tcount\tprobability\tcontentionCentroid\tbodyCentroid\tcontentionVariance\tbodyVariance\tcontentionBodyCovariance\tradiusSquared\tradius",
                lines.get(0));

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportTransitionsTsvEmptyResultsPerformsNoWrites(@TempDir Path tempDir) throws Exception {
        TrialExport.exportTransitionsTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.TRANSITIONS_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.TRANSITIONS_CHECKSUM)));

        TrialExport.exportTransitionsTsv(null, List.of());
        TrialExport.exportTransitionsTsv(tempDir, null);
    }

    @Test
    void testExportTransitionsTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        CoreIterationResult r0c0 = createPopulatedResult(0, 0);
        List<List<CoreIterationResult>> results = List.of(List.of(r0c0));

        TrialExport.exportTransitionsTsv(tempDir, results);

        Path tsv = tempDir.resolve(Constants.TRANSITIONS_TSV);
        Path checksum = tempDir.resolve(Constants.TRANSITIONS_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        // 1 header + 2 (decisionTypes) * 2 (segments) * 25 * 25 = 1 + 2500 = 2501 lines
        assertEquals(2501, lines.size());
        assertEquals(
                "iteration\tcore\tdecisionType\tsegment\tfromState\tfromContention\tfromBody\ttoState\ttoContention\ttoBody\tcount\tprobability\tselfTransitionRate\tdominantOutgoingState\tdominantOutgoingProbability",
                lines.get(0));

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportVectorFieldsTsvEmptyResultsPerformsNoWrites(@TempDir Path tempDir) throws Exception {
        TrialExport.exportVectorFieldsTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.VECTOR_FIELDS_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.VECTOR_FIELDS_CHECKSUM)));

        TrialExport.exportVectorFieldsTsv(null, List.of());
        TrialExport.exportVectorFieldsTsv(tempDir, null);
    }

    @Test
    void testExportVectorFieldsTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        CoreIterationResult r0c0 = createPopulatedResult(0, 0);
        List<List<CoreIterationResult>> results = List.of(List.of(r0c0));

        TrialExport.exportVectorFieldsTsv(tempDir, results);

        Path tsv = tempDir.resolve(Constants.VECTOR_FIELDS_TSV);
        Path checksum = tempDir.resolve(Constants.VECTOR_FIELDS_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        // 1 header + 2 (decisionTypes) * 2 (segments) * 25 (cells) = 1 + 100 = 101 lines
        assertEquals(101, lines.size());
        assertEquals(
                "iteration\tcore\tdecisionType\tsegment\tcontentionBand\tbodyBand\ttransitionCount\tmeanDeltaContention\tmeanDeltaBody\tmagnitude",
                lines.get(0));

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportCorrelationsTsvEmptyResultsPerformsNoWrites(@TempDir Path tempDir) throws Exception {
        TrialExport.exportCorrelationsTsv(tempDir, List.of());
        assertFalse(Files.exists(tempDir.resolve(Constants.CORRELATIONS_TSV)));
        assertFalse(Files.exists(tempDir.resolve(Constants.CORRELATIONS_CHECKSUM)));

        TrialExport.exportCorrelationsTsv(null, List.of());
        TrialExport.exportCorrelationsTsv(tempDir, null);
    }

    @Test
    void testExportCorrelationsTsvWritesDataAndChecksum(@TempDir Path tempDir) throws Exception {
        CoreIterationResult r0c0 = createPopulatedResult(0, 0);
        List<List<CoreIterationResult>> results = List.of(List.of(r0c0));

        TrialExport.exportCorrelationsTsv(tempDir, results);

        Path tsv = tempDir.resolve(Constants.CORRELATIONS_TSV);
        Path checksum = tempDir.resolve(Constants.CORRELATIONS_CHECKSUM);

        assertTrue(Files.exists(tsv));
        assertTrue(Files.exists(checksum));

        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        // 1 header + 3 segments * (7*7 + 2*2 + 3*3 + 3*3 + 3*3) = 1 + 3 * (49 + 4 + 9 + 9 + 9) = 1 + 3 * 80 = 241 lines
        assertEquals(241, lines.size());
        assertEquals("iteration\tcore\tmetric\tsegment\tvariable1\tvariable2\tpearson\tspearman", lines.get(0));

        String expectedHash = computeSha256(tsv);
        String storedHash = Files.readString(checksum, StandardCharsets.UTF_8).trim();
        assertEquals(expectedHash, storedHash);
    }

    @Test
    void testExportAllPerIterationFalseWritesOnlyTopLevel(@TempDir Path tempDir) throws Exception {
        CoreIterationResult r0 = createPopulatedResult(0, 0);
        CoreIterationResult r1 = createPopulatedResult(1, 0);
        List<List<CoreIterationResult>> results = List.of(List.of(r0), List.of(r1));

        TrialExport.exportAll(tempDir, results, false);

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
    void testExportAllPerIterationTrueWritesPerIterationSubdirectories(@TempDir Path tempDir) throws Exception {
        CoreIterationResult r0 = createPopulatedResult(0, 0);
        CoreIterationResult r1 = createPopulatedResult(1, 0);
        List<List<CoreIterationResult>> results = List.of(List.of(r0), List.of(r1));

        TrialExport.exportAll(tempDir, results, true);

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
