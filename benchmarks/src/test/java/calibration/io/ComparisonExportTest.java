package calibration.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.PerformanceComparisonCalculator;
import calibration.comparisons.SystemTelemetryComparisonCalculator;
import calibration.comparisons.schema.CandidateComparison;
import calibration.comparisons.schema.ComparisonCompatibility;
import calibration.comparisons.schema.ComparisonResult;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.ConfigurationDifference;
import calibration.comparisons.schema.DifferenceCategory;
import calibration.comparisons.schema.OccupancyComparison;
import calibration.comparisons.schema.PerformanceComparison;
import calibration.comparisons.schema.RunArtifacts;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ScalarComparison;
import calibration.comparisons.schema.ThroughputResult;
import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.ComparisonStrategy;
import calibration.config.TrialConfig;
import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.infra.Constants;
import calibration.statistics.HighSpeedMetricsStatistics;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.ScalarSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
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

/// Comprehensive unit tests for ComparisonExport artifact serialization, checksumming, deterministic formatting, and
/// compatibility handling.
class ComparisonExportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

        metrics.recordExec(1, 1, 0, 3, 250 + offset, 30.0 + offset);
        metrics.recordExec(2, 2, 1, 4, 350 + offset, 40.0 + offset);

        return metrics;
    }

    private static SystemForkResult createForkSystem(int offset) {
        List<List<HighSpeedMetrics>> allIterMetrics = new ArrayList<>();
        for (int iter = 0; iter < 2; iter++) {
            List<HighSpeedMetrics> coreMetrics = new ArrayList<>();
            coreMetrics.add(createPopulatedMetrics(offset + iter));
            coreMetrics.add(createPopulatedMetrics(offset + iter + 1));
            allIterMetrics.add(coreMetrics);
        }
        return HighSpeedMetricsStatistics.calculateSystemFork(0, allIterMetrics);
    }

    private static CompletedRun createCompletedRun(
            String id, String name, String group, String sourcePath, double baseScore, int metricOffset) {
        RunIdentity identity = new RunIdentity(id, name, group, 0, null, sourcePath);

        CalibrationBenchmarkConfig calConfig = new CalibrationBenchmarkConfig(
                List.of(1, 2), 2, 1, 100, false, 1000L, 5000L, 1024, true, true, true, true, true, true);

        TrialConfig trialConfig = new TrialConfig(
                id,
                name,
                group,
                "Description for " + id,
                "Hypothesis for " + id,
                null,
                null,
                null,
                true,
                null,
                1,
                1,
                2,
                "1s",
                "2s",
                List.of(),
                null,
                calConfig);

        ThroughputResult throughput = new ThroughputResult(
                baseScore,
                baseScore * 0.02,
                "ops/s",
                List.of(baseScore, baseScore * 1.05),
                List.of(baseScore, baseScore * 1.05));

        SystemForkResult system = createForkSystem(metricOffset);
        RunArtifacts artifacts = RunArtifacts.standard(sourcePath);

        return new CompletedRun(identity, trialConfig, throughput, system, List.of(), artifacts);
    }

    @Test
    void testIncompatibleComparisonExportsIdentityAndReasons(@TempDir Path tempDir) throws Exception {
        CompletedRun baseline = createCompletedRun("base-trial", "Baseline Trial", "grp-1", "/runs/base", 1000.0, 0);
        CompletedRun incompatibleCand =
                createCompletedRun("incompat-cand", "Incompatible Candidate", "grp-1", "/runs/incompat", 1000.0, 0);

        ComparisonCompatibility incompatCompat = ComparisonCompatibility.incompatible(
                List.of(new ConfigurationDifference(
                        "/jvmArgs", TextNode.valueOf("-Xmx2g"), TextNode.valueOf("-Xmx4g"), DifferenceCategory.JVM)),
                List.of("JVM argument differences are incompatible"));

        CandidateComparison comp = new CandidateComparison(
                0,
                baseline.identity(),
                incompatibleCand.identity(),
                null,
                incompatCompat,
                incompatCompat.differences(),
                null,
                List.of(),
                null);

        ComparisonResult result = new ComparisonResult(ComparisonStrategy.BASELINE, List.of(comp));

        ComparisonExport.export(tempDir, result);

        // Check summary row has UNAVAILABLE outcome and NaN for missing stats
        Path summaryFile = tempDir.resolve(Constants.COMPARISON_SUMMARY_TSV);
        List<String> summaryLines = Files.readAllLines(summaryFile, StandardCharsets.UTF_8);
        assertEquals(2, summaryLines.size());
        String[] row = summaryLines.get(1).split("\t");
        assertEquals("BASELINE", row[0]);
        assertEquals("0", row[1]);
        assertEquals("", row[2]);
        assertEquals("base-trial", row[3]);
        assertEquals("incompat-cand", row[4]);
        assertEquals("INCOMPATIBLE", row[5]);
        assertEquals("NaN", row[6]);
        assertEquals("UNAVAILABLE", row[19]);

        // Check config differences still exported
        Path diffFile = tempDir.resolve(Constants.CONFIGURATION_DIFFERENCES_TSV);
        List<String> diffLines = Files.readAllLines(diffFile, StandardCharsets.UTF_8);
        assertEquals(2, diffLines.size());
        assertTrue(diffLines.get(1).contains("/jvmArgs"));

        // Check manifest contains incompatibility status and reason
        Path manifestFile = tempDir.resolve(Constants.COMPARISON_MANIFEST_JSON);
        JsonNode manifest = MAPPER.readTree(manifestFile.toFile());
        assertEquals(
                "INCOMPATIBLE",
                manifest.get("pairs").get(0).get("compatibilityStatus").asText());
        assertEquals(
                "JVM argument differences are incompatible",
                manifest.get("pairs").get(0).get("compatibilityReasons").get(0).asText());
    }

    @Test
    void testStringEscapingSanitizesTabsAndNewlines(@TempDir Path tempDir) throws Exception {
        CompletedRun baseline = createCompletedRun("base\ttab", "Baseline\nName", "grp-1", "/runs/base", 1000.0, 0);
        CompletedRun cand =
                createCompletedRun("cand\twith\ttabs", "Candidate\r\nName", "grp-1", "/runs/cand", 1100.0, 5);

        ComparisonCompatibility compat = ComparisonCompatibility.compatible();
        List<ConfigurationDifference> diffs = List.of(new ConfigurationDifference(
                "/notes",
                TextNode.valueOf("line1\nline2\ttab"),
                TextNode.valueOf("cand\nline"),
                DifferenceCategory.IDENTITY));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(baseline, cand, compat);
        var agg = SystemTelemetryComparisonCalculator.compare(baseline, cand, compat);

        CandidateComparison comp = new CandidateComparison(
                0, baseline.identity(), cand.identity(), null, compat, diffs, perf, List.of(), agg);

        ComparisonResult result = new ComparisonResult(ComparisonStrategy.BASELINE, List.of(comp));

        ComparisonExport.export(tempDir, result);

        Path diffTsv = tempDir.resolve(Constants.CONFIGURATION_DIFFERENCES_TSV);
        List<String> lines = Files.readAllLines(diffTsv, StandardCharsets.UTF_8);
        assertEquals(2, lines.size()); // Header + 1 row (no extra lines caused by embedded newlines)
        String[] tokens = lines.get(1).split("\t");
        assertEquals(10, tokens.length); // 10 columns
        assertEquals("base\\ttab", tokens[3]);
        assertEquals("cand\\twith\\ttabs", tokens[4]);
    }

    @Test
    void testConstantsMatchExpectedFilenames() {
        assertEquals("comparison_manifest.json", Constants.COMPARISON_MANIFEST_JSON);
        assertEquals("comparison_manifest.json.sha256", Constants.COMPARISON_MANIFEST_CHECKSUM);
        assertEquals("comparison_summary.tsv", Constants.COMPARISON_SUMMARY_TSV);
        assertEquals("comparison_summary.tsv.sha256", Constants.COMPARISON_SUMMARY_CHECKSUM);
        assertEquals("configuration_differences.tsv", Constants.CONFIGURATION_DIFFERENCES_TSV);
        assertEquals("configuration_differences.tsv.sha256", Constants.CONFIGURATION_DIFFERENCES_CHECKSUM);
        assertEquals("scalar_comparisons.tsv", Constants.SCALAR_COMPARISONS_TSV);
        assertEquals("scalar_comparisons.tsv.sha256", Constants.SCALAR_COMPARISONS_CHECKSUM);
        assertEquals("occupancy_comparisons.tsv", Constants.OCCUPANCY_COMPARISONS_TSV);
        assertEquals("occupancy_comparisons.tsv.sha256", Constants.OCCUPANCY_COMPARISONS_CHECKSUM);
        assertEquals("transition_comparisons.tsv", Constants.TRANSITION_COMPARISONS_TSV);
        assertEquals("transition_comparisons.tsv.sha256", Constants.TRANSITION_COMPARISONS_CHECKSUM);
        assertEquals("vector_field_comparisons.tsv", Constants.VECTOR_FIELD_COMPARISONS_TSV);
        assertEquals("vector_field_comparisons.tsv.sha256", Constants.VECTOR_FIELD_COMPARISONS_CHECKSUM);
        assertEquals("correlation_comparisons.tsv", Constants.CORRELATION_COMPARISONS_TSV);
        assertEquals("correlation_comparisons.tsv.sha256", Constants.CORRELATION_COMPARISONS_CHECKSUM);
    }

    @Test
    void testPartialComparisonExportsAvailableDataOnly(@TempDir Path tempDir) throws Exception {
        CompletedRun baseline = createCompletedRun("base-trial", "Baseline Trial", "grp-1", "/runs/base", 1000.0, 0);
        CompletedRun cand = createCompletedRun("cand-partial", "Candidate Partial", "grp-1", "/runs/cand", 1100.0, 5);

        ComparisonCompatibility partialCompat = ComparisonCompatibility.partial(
                List.of(new ConfigurationDifference(
                        "/observeBatchProgress",
                        TextNode.valueOf("true"),
                        TextNode.valueOf("false"),
                        DifferenceCategory.OBSERVATION)),
                List.of("Observation configuration differs at /observeBatchProgress"));

        PerformanceComparison perf = PerformanceComparisonCalculator.compare(baseline, cand, partialCompat);

        // Build aggregate with only occupancy and empty transitions/vectors
        BranchOccupancyResult baseOcc = baseline.system().idleOccupancy();
        BranchOccupancyResult candOcc = cand.system().idleOccupancy();
        OccupancyComparison occComp = SystemTelemetryComparisonCalculator.compareOccupancy(baseOcc, candOcc);

        calibration.comparisons.schema.AggregateComparison partialAgg =
                new calibration.comparisons.schema.AggregateComparison(
                        occComp,
                        occComp,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        java.util.Map.of(
                                "cycleStart.head.throughput",
                                new ScalarComparison(
                                        ScalarSummary.of(100.0, 110.0),
                                        ScalarSummary.of(120.0, 130.0),
                                        20.0,
                                        20.0,
                                        0.0,
                                        0.0,
                                        0.0,
                                        20.0,
                                        20.0,
                                        20.0,
                                        20.0,
                                        20.0,
                                        20.0,
                                        0.0,
                                        0.0,
                                        0.0)),
                        java.util.Map.of());

        CandidateComparison comp = new CandidateComparison(
                0,
                baseline.identity(),
                cand.identity(),
                null,
                partialCompat,
                partialCompat.differences(),
                perf,
                List.of(),
                partialAgg);

        ComparisonResult result = new ComparisonResult(ComparisonStrategy.BASELINE, List.of(comp));

        ComparisonExport.export(tempDir, result);

        // Transition TSV must only have header (0 data rows because transition comparison is null)
        Path transTsv = tempDir.resolve(Constants.TRANSITION_COMPARISONS_TSV);
        List<String> transLines = Files.readAllLines(transTsv, StandardCharsets.UTF_8);
        assertEquals(1, transLines.size());

        // Vector fields TSV must only have header
        Path vfTsv = tempDir.resolve(Constants.VECTOR_FIELD_COMPARISONS_TSV);
        List<String> vfLines = Files.readAllLines(vfTsv, StandardCharsets.UTF_8);
        assertEquals(1, vfLines.size());

        // Scalar TSV must have header + 1 row
        Path scalarTsv = tempDir.resolve(Constants.SCALAR_COMPARISONS_TSV);
        List<String> scalarLines = Files.readAllLines(scalarTsv, StandardCharsets.UTF_8);
        assertEquals(2, scalarLines.size());
    }

    @Test
    void testMultipleCandidatesExportIndependentlyWithoutRanking(@TempDir Path tempDir) throws Exception {
        // Candidate 1 has higher throughput (+20%), Candidate 2 has lower (-10%), Candidate 3 has huge (+100%)
        CompletedRun baseline = createCompletedRun("base-trial", "Baseline Trial", "grp-1", "/runs/base", 1000.0, 0);
        CompletedRun cand1 = createCompletedRun("cand-mid", "Cand Mid", "grp-1", "/runs/cand1", 1200.0, 5);
        CompletedRun cand2 = createCompletedRun("cand-low", "Cand Low", "grp-1", "/runs/cand2", 900.0, 10);
        CompletedRun cand3 = createCompletedRun("cand-high", "Cand High", "grp-1", "/runs/cand3", 2000.0, 15);

        ComparisonCompatibility compat = ComparisonCompatibility.compatible();
        PerformanceComparison perf1 = PerformanceComparisonCalculator.compare(baseline, cand1, compat);
        PerformanceComparison perf2 = PerformanceComparisonCalculator.compare(baseline, cand2, compat);
        PerformanceComparison perf3 = PerformanceComparisonCalculator.compare(baseline, cand3, compat);

        CandidateComparison comp1 = new CandidateComparison(
                0, baseline.identity(), cand1.identity(), null, compat, List.of(), perf1, List.of(), null);
        CandidateComparison comp2 = new CandidateComparison(
                1, baseline.identity(), cand2.identity(), null, compat, List.of(), perf2, List.of(), null);
        CandidateComparison comp3 = new CandidateComparison(
                2, baseline.identity(), cand3.identity(), null, compat, List.of(), perf3, List.of(), null);

        // Feed in order: cand-mid, cand-low, cand-high
        ComparisonResult result = new ComparisonResult(ComparisonStrategy.BASELINE, List.of(comp1, comp2, comp3));

        ComparisonExport.exportComparisonSummaryTsv(tempDir, result);

        Path summaryTsv = tempDir.resolve(Constants.COMPARISON_SUMMARY_TSV);
        List<String> lines = Files.readAllLines(summaryTsv, StandardCharsets.UTF_8);
        assertEquals(4, lines.size());

        // Must preserve order (comp1 -> comp2 -> comp3) and not rank by throughput!
        assertEquals("cand-mid", lines.get(1).split("\t")[4]);
        assertEquals("cand-low", lines.get(2).split("\t")[4]);
        assertEquals("cand-high", lines.get(3).split("\t")[4]);
    }

    @Test
    void testMissingValuesNotSerializedAsZero(@TempDir Path tempDir) throws Exception {
        CompletedRun baseline = createCompletedRun("base-trial", "Baseline Trial", "grp-1", "/runs/base", 1000.0, 0);
        CompletedRun cand = createCompletedRun("cand-trial", "Cand Trial", "grp-1", "/runs/cand", 1000.0, 5);

        // Scalar comparison with NaN stats
        ScalarSummary emptySummary = ScalarSummary.empty();
        ScalarComparison sc = new ScalarComparison(
                emptySummary,
                emptySummary,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN);

        calibration.comparisons.schema.AggregateComparison agg = new calibration.comparisons.schema.AggregateComparison(
                SystemTelemetryComparisonCalculator.compareOccupancy(
                        baseline.system().idleOccupancy(), cand.system().idleOccupancy()),
                SystemTelemetryComparisonCalculator.compareOccupancy(
                        baseline.system().execOccupancy(), cand.system().execOccupancy()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                java.util.Map.of("cycleStart.head.throughput", sc),
                java.util.Map.of());

        CandidateComparison comp = new CandidateComparison(
                0,
                baseline.identity(),
                cand.identity(),
                null,
                ComparisonCompatibility.compatible(),
                List.of(),
                PerformanceComparisonCalculator.compare(baseline, cand, ComparisonCompatibility.compatible()),
                List.of(),
                agg);

        ComparisonResult result = new ComparisonResult(ComparisonStrategy.BASELINE, List.of(comp));

        ComparisonExport.exportScalarComparisonsTsv(tempDir, result);

        Path scalarFile = tempDir.resolve(Constants.SCALAR_COMPARISONS_TSV);
        List<String> lines = Files.readAllLines(scalarFile, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        String[] tokens = lines.get(1).split("\t");

        // tokens[11] is baselineMean, tokens[12] is candidateMean
        assertEquals("NaN", tokens[11]);
        assertEquals("NaN", tokens[12]);
        assertEquals("NaN", tokens[13]); // meanDelta
    }

    @Test
    void testFormatDoubleFullDecimalRepresentation() {
        assertEquals("NaN", ComparisonExport.formatDouble(Double.NaN));
        assertEquals("Infinity", ComparisonExport.formatDouble(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", ComparisonExport.formatDouble(Double.NEGATIVE_INFINITY));
        assertEquals("0.0", ComparisonExport.formatDouble(0.0));
        assertEquals("12345.678", ComparisonExport.formatDouble(12345.678));
        assertEquals("418189300", ComparisonExport.formatDouble(418189300.0));
        assertEquals("0.00000123", ComparisonExport.formatDouble(0.00000123));
        assertTrue(!ComparisonExport.formatDouble(418189300.0).contains("E"), "Must not contain scientific exponent");
        assertTrue(!ComparisonExport.formatDouble(0.00000123).contains("E"), "Must not contain scientific exponent");
    }

    @Test
    void testComparisonSummaryWritesFullDecimalNumbers(@TempDir Path tempDir) throws Exception {
        CompletedRun baseRun = createCompletedRun("base-1", "Base", "group", "path/base", 50000000.0, 0);
        CompletedRun candRun = createCompletedRun("cand-1", "Cand", "group", "path/cand", 75000000.0, 10);

        PerformanceComparison perf =
                PerformanceComparisonCalculator.compare(baseRun, candRun, ComparisonCompatibility.compatible());

        CandidateComparison comp = new CandidateComparison(
                0,
                baseRun.identity(),
                candRun.identity(),
                null,
                ComparisonCompatibility.compatible(),
                List.of(),
                perf,
                List.of(),
                null);

        ComparisonResult result = new ComparisonResult(ComparisonStrategy.BASELINE, List.of(comp));
        ComparisonExport.exportComparisonSummaryTsv(tempDir, result);

        Path summaryTsv = tempDir.resolve(Constants.COMPARISON_SUMMARY_TSV);
        String content = Files.readString(summaryTsv, StandardCharsets.UTF_8);

        assertTrue(!content.contains("E+"), "Summary TSV should not have scientific notation E+");
        assertTrue(!content.contains("E-"), "Summary TSV should not have scientific notation E-");
        assertTrue(
                content.contains("50000000") || content.contains("51250000"),
                "Should contain full decimal number: " + content);
    }
}
