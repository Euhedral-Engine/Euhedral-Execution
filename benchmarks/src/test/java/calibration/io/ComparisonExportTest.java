package calibration.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.ComparisonCompatibilityAnalyzer;
import calibration.comparisons.PerformanceComparisonCalculator;
import calibration.comparisons.SystemTelemetryComparisonCalculator;
import calibration.comparisons.TrialConfigDiffer;
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
import calibration.statistics.ComparisonOutcome;
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

        metrics.recordExec(1, 1, 2, 3, 250 + offset, 30.0 + offset);
        metrics.recordExec(2, 2, 3, 4, 350 + offset, 40.0 + offset);

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
            String id,
            String name,
            String group,
            String sourcePath,
            io.euhedral_execution.core.config.FragmentDecisionWeights decisionWeights,
            double baseScore,
            int metricOffset) {
        RunIdentity identity = new RunIdentity(id, name, group, 0, null, sourcePath);

        CalibrationBenchmarkConfig calConfig = new CalibrationBenchmarkConfig(
                List.of(1, 2),
                2,
                1,
                100,
                false,
                1000L,
                5000L,
                null,
                decisionWeights != null
                        ? decisionWeights
                        : io.euhedral_execution.core.config.FragmentDecisionWeights.DEFAULT,
                1024,
                true,
                true,
                true,
                true,
                true,
                true);

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

    private static ComparisonResult createPopulatedComparisonResult() {
        var baseWeights = io.euhedral_execution.core.config.FragmentDecisionWeights.DEFAULT;

        List<io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPolicy> policies1 =
                new ArrayList<>(baseWeights.executionPolicies());
        var p4_1 = policies1.get(4);
        policies1.set(
                4,
                new io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPolicy(
                        io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath.STAGED,
                        p4_1.sBody(),
                        p4_1.mBody(),
                        p4_1.hBody(),
                        p4_1.xhBody()));
        var cand1Weights = new io.euhedral_execution.core.config.FragmentDecisionWeights(
                baseWeights.idleContentionThresholds(),
                baseWeights.idleBodyCostWeights(),
                baseWeights.idleTimeNs(),
                baseWeights.execContentionThresholds(),
                baseWeights.execBodyCostWeights(),
                policies1);

        List<io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPolicy> policies2 =
                new ArrayList<>(baseWeights.executionPolicies());
        var p0_2 = policies2.get(0);
        policies2.set(
                0,
                new io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPolicy(
                        io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath.STAGED,
                        p0_2.sBody(),
                        p0_2.mBody(),
                        p0_2.hBody(),
                        p0_2.xhBody()));
        var cand2Weights = new io.euhedral_execution.core.config.FragmentDecisionWeights(
                baseWeights.idleContentionThresholds(),
                baseWeights.idleBodyCostWeights(),
                baseWeights.idleTimeNs(),
                baseWeights.execContentionThresholds(),
                baseWeights.execBodyCostWeights(),
                policies2);

        CompletedRun baseline =
                createCompletedRun("base-trial", "Baseline Trial", "grp-1", "/runs/base", baseWeights, 1000.0, 0);
        CompletedRun candidate1 =
                createCompletedRun("cand-trial-1", "Candidate 1", "grp-1", "/runs/cand1", cand1Weights, 1200.0, 10);
        CompletedRun candidate2 =
                createCompletedRun("cand-trial-2", "Candidate 2", "grp-1", "/runs/cand2", cand2Weights, 900.0, 20);

        ComparisonCompatibility compat1 = ComparisonCompatibilityAnalyzer.analyze(baseline, candidate1);
        List<ConfigurationDifference> diffs1 = TrialConfigDiffer.diff(baseline.trialConfig(), candidate1.trialConfig());
        PerformanceComparison perf1 = PerformanceComparisonCalculator.compare(baseline, candidate1, compat1);
        var agg1 = SystemTelemetryComparisonCalculator.compare(baseline, candidate1, compat1);
        CandidateComparison comp1 = new CandidateComparison(
                0, baseline.identity(), candidate1.identity(), null, compat1, diffs1, perf1, List.of(), agg1);

        ComparisonCompatibility compat2 = ComparisonCompatibilityAnalyzer.analyze(baseline, candidate2);
        List<ConfigurationDifference> diffs2 = TrialConfigDiffer.diff(baseline.trialConfig(), candidate2.trialConfig());
        PerformanceComparison perf2 = PerformanceComparisonCalculator.compare(baseline, candidate2, compat2);
        var agg2 = SystemTelemetryComparisonCalculator.compare(baseline, candidate2, compat2);
        CandidateComparison comp2 = new CandidateComparison(
                1, baseline.identity(), candidate2.identity(), null, compat2, diffs2, perf2, List.of(), agg2);

        return new ComparisonResult(ComparisonStrategy.BASELINE, List.of(comp1, comp2));
    }

    @Test
    void testExportCreatesAllArtifactsAndChecksums(@TempDir Path tempDir) throws Exception {
        ComparisonResult result = createPopulatedComparisonResult();
        ComparisonExport.export(tempDir, result);

        String[] expectedFiles = {
            Constants.COMPARISON_MANIFEST_JSON,
            Constants.COMPARISON_SUMMARY_TSV,
            Constants.CONFIGURATION_DIFFERENCES_TSV,
            Constants.SCALAR_COMPARISONS_TSV,
            Constants.OCCUPANCY_COMPARISONS_TSV,
            Constants.TRANSITION_COMPARISONS_TSV,
            Constants.VECTOR_FIELD_COMPARISONS_TSV,
            Constants.CORRELATION_COMPARISONS_TSV
        };

        for (String filename : expectedFiles) {
            Path file = tempDir.resolve(filename);
            Path checksumFile = tempDir.resolve(filename + ".sha256");

            assertTrue(Files.exists(file), "File must exist: " + filename);
            assertTrue(Files.exists(checksumFile), "Checksum must exist for: " + filename);

            String computed = computeSha256(file);
            String stored =
                    Files.readString(checksumFile, StandardCharsets.UTF_8).trim();
            assertEquals(computed, stored, "Checksum mismatch for " + filename);
        }
    }

    @Test
    void testComparisonSummarySchemaAndValues(@TempDir Path tempDir) throws Exception {
        ComparisonResult result = createPopulatedComparisonResult();
        ComparisonExport.exportComparisonSummaryTsv(tempDir, result);

        Path tsv = tempDir.resolve(Constants.COMPARISON_SUMMARY_TSV);
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);

        // Header + 2 candidate rows
        assertEquals(3, lines.size());
        assertEquals(
                "strategy\tpairIndex\tkey\tbaseline\tcandidate\tcompatibilityStatus\tbaselineMean\tcandidateMean\tunit\tabsoluteDelta\trelativeDeltaPercent\tbaselineVariance\tcandidateVariance\tbaselineStdDev\tcandidateStdDev\tbaselineCv\tcandidateCv\tbaselineForkCount\tcandidateForkCount\toutcome",
                lines.get(0));

        String[] row1 = lines.get(1).split("\t");
        assertEquals("BASELINE", row1[0]);
        assertEquals("0", row1[1]);
        assertEquals("", row1[2]);
        assertEquals("base-trial", row1[3]);
        assertEquals("cand-trial-1", row1[4]);
        assertEquals("COMPATIBLE", row1[5]);
        assertEquals("1025.0", row1[6]);
        assertEquals("1230.0", row1[7]);
        assertEquals("ops/s", row1[8]);
        assertEquals("205.0", row1[9]);
        assertEquals("20.0", row1[10]); // relative delta %
        assertEquals("2", row1[17]); // baselineForkCount
        assertEquals("2", row1[18]); // candidateForkCount
        assertEquals(ComparisonOutcome.B_BETTER.name(), row1[19]);

        String[] row2 = lines.get(2).split("\t");
        assertEquals("BASELINE", row2[0]);
        assertEquals("1", row2[1]);
        assertEquals("", row2[2]);
        assertEquals("base-trial", row2[3]);
        assertEquals("cand-trial-2", row2[4]);
        assertEquals("COMPATIBLE", row2[5]);
        assertEquals("1025.0", row2[6]);
        assertEquals("922.5", row2[7]);
        assertEquals(ComparisonOutcome.A_BETTER.name(), row2[19]);
    }

    @Test
    void testConfigurationDifferencesOrderingAndJsonPointers(@TempDir Path tempDir) throws Exception {
        ComparisonResult result = createPopulatedComparisonResult();
        ComparisonExport.exportConfigurationDifferencesTsv(tempDir, result);

        Path tsv = tempDir.resolve(Constants.CONFIGURATION_DIFFERENCES_TSV);
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);

        assertTrue(lines.size() > 1);
        assertEquals(
                "strategy\tpairIndex\tkey\tbaseline\tcandidate\tcompatibilityStatus\tcategory\tpath\tbaselineValue\tcandidateValue",
                lines.get(0));

        // Check paths and determinism
        String prevPath = "";
        String prevCand = "";
        for (int i = 1; i < lines.size(); i++) {
            String[] tokens = lines.get(i).split("\t");
            String cand = tokens[4];
            String path = tokens[7];

            if (cand.equals(prevCand)) {
                assertTrue(path.compareTo(prevPath) >= 0, "Diff paths must be sorted: " + path + " vs " + prevPath);
            }
            prevCand = cand;
            prevPath = path;

            assertTrue(path.startsWith("/"), "Path must be a valid JSON pointer: " + path);
        }
    }

    @Test
    void testScalarComparisonsColumnsAndDistinctLabels(@TempDir Path tempDir) throws Exception {
        ComparisonResult result = createPopulatedComparisonResult();
        ComparisonExport.exportScalarComparisonsTsv(tempDir, result);

        Path tsv = tempDir.resolve(Constants.SCALAR_COMPARISONS_TSV);
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);

        assertTrue(lines.size() > 1);
        assertEquals(
                "strategy\tpairIndex\tkey\tbaseline\tcandidate\tscope\tcategory\tsegment\tmetric\tbaselineCount\tcandidateCount\tbaselineMean\tcandidateMean\tmeanDelta\tbaselineMedian\tcandidateMedian\tmedianDelta\tbaselineVariance\tcandidateVariance\tvarianceDelta\tbaselineStdDev\tcandidateStdDev\tstdDevDelta\tbaselineCv\tcandidateCv\tcvDelta\tbaselineP25\tcandidateP25\tp25Delta\tbaselineP50\tcandidateP50\tp50Delta\tbaselineP75\tcandidateP75\tp75Delta\tbaselineP95\tcandidateP95\tp95Delta\tbaselineIqr\tcandidateIqr\tiqrDelta\tbaselineNormalizedIqr\tcandidateNormalizedIqr\tnormalizedIqrDelta\tbaselineP95ToP50\tcandidateP95ToP50\tp95ToP50Delta",
                lines.get(0));

        boolean foundHead = false;
        boolean foundSteadyState = false;
        boolean foundCombined = false;
        boolean foundIdle = false;
        boolean foundExec = false;

        for (int i = 1; i < lines.size(); i++) {
            String[] tokens = lines.get(i).split("\t");
            String category = tokens[6];
            String segment = tokens[7];

            if ("head".equals(segment)) foundHead = true;
            if ("steady_state".equals(segment)) foundSteadyState = true;
            if ("combined".equals(segment)) foundCombined = true;
            if ("idle_decision".equals(category)) foundIdle = true;
            if ("exec_decision".equals(category)) foundExec = true;
        }

        assertTrue(foundHead, "head segment must be present");
        assertTrue(foundSteadyState, "steady_state segment must be present");
        assertTrue(foundCombined, "combined segment must be present");
        assertTrue(foundIdle, "idle_decision category must be present");
        assertTrue(foundExec, "exec_decision category must be present");
    }

    @Test
    void testOccupancyComparisons25CellsAndTvd(@TempDir Path tempDir) throws Exception {
        ComparisonResult result = createPopulatedComparisonResult();
        ComparisonExport.exportOccupancyComparisonsTsv(tempDir, result);

        Path tsv = tempDir.resolve(Constants.OCCUPANCY_COMPARISONS_TSV);
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);

        // Header + 2 candidates * 2 decisionTypes (idle, exec) * 25 cells = 1 + 100 = 101 lines
        assertEquals(101, lines.size());
        assertEquals(
                "strategy\tpairIndex\tkey\tbaseline\tcandidate\tdecisionType\tcontentionBand\tbodyBand\tbaselineCount\tcandidateCount\tcountDelta\tbaselineProbability\tcandidateProbability\tprobabilityDelta\tbaselineContentionCentroid\tcandidateContentionCentroid\tcontentionCentroidDelta\tbaselineBodyCentroid\tcandidateBodyCentroid\tbodyCentroidDelta\tcentroidDistance\tbaselineContentionVariance\tcandidateContentionVariance\tcontentionVarianceDelta\tbaselineBodyVariance\tcandidateBodyVariance\tbodyVarianceDelta\tbaselineCovariance\tcandidateCovariance\tcovarianceDelta\tbaselineRadius\tcandidateRadius\tradiusDelta\ttotalVariationDistance",
                lines.get(0));

        // Verify count and probability not swapped: probability must be in [0.0, 1.0]
        for (int i = 1; i < lines.size(); i++) {
            String[] tokens = lines.get(i).split("\t");
            long baseCount = Long.parseLong(tokens[8]);
            long candCount = Long.parseLong(tokens[9]);
            double baseProb = Double.parseDouble(tokens[11]);
            double candProb = Double.parseDouble(tokens[12]);
            double tvd = Double.parseDouble(tokens[tokens.length - 1]);

            assertTrue(baseCount >= 0L);
            assertTrue(candCount >= 0L);
            assertTrue(baseProb >= 0.0 && baseProb <= 1.0, "baseProb out of range: " + baseProb);
            assertTrue(candProb >= 0.0 && candProb <= 1.0, "candProb out of range: " + candProb);
            assertTrue(tvd >= 0.0 && tvd <= 1.0, "TVD out of range: " + tvd);
        }
    }

    @Test
    void testTransitionComparisonsFromToPreserved(@TempDir Path tempDir) throws Exception {
        ComparisonResult result = createPopulatedComparisonResult();
        ComparisonExport.exportTransitionComparisonsTsv(tempDir, result);

        Path tsv = tempDir.resolve(Constants.TRANSITION_COMPARISONS_TSV);
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);

        // Header + 2 candidates * 2 decisionTypes (idle, exec) * 2 segments (head, steady_state) * 625 = 1 + 5000 =
        // 5001 lines
        assertEquals(5001, lines.size());
        assertEquals(
                "strategy\tpairIndex\tkey\tbaseline\tcandidate\tdecisionType\tsegment\tfromState\tfromContention\tfromBody\ttoState\ttoContention\ttoBody\tbaselineCount\tcandidateCount\tcountDelta\tbaselineProbability\tcandidateProbability\tprobabilityDelta\tbaselineSelfTransitionRate\tcandidateSelfTransitionRate\tselfTransitionRateDelta\tbaselineDominantOutgoingState\tcandidateDominantOutgoingState\tdominantStateChanged\tbaselineDominantProbability\tcandidateDominantProbability\tdominantProbabilityDelta",
                lines.get(0));

        String[] row1 = lines.get(1).split("\t");
        assertEquals("0", row1[7]); // fromState 0
        assertEquals("0", row1[8]); // fromContention 0
        assertEquals("0", row1[9]); // fromBody 0
        assertEquals("0", row1[10]); // toState 0
        assertEquals("0", row1[11]); // toContention 0
        assertEquals("0", row1[12]); // toBody 0
    }

    @Test
    void testVectorFieldsAll25Cells(@TempDir Path tempDir) throws Exception {
        ComparisonResult result = createPopulatedComparisonResult();
        ComparisonExport.exportVectorFieldComparisonsTsv(tempDir, result);

        Path tsv = tempDir.resolve(Constants.VECTOR_FIELD_COMPARISONS_TSV);
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);

        // Header + 2 candidates * 2 decisionTypes * 2 segments * 25 cells = 1 + 200 = 201 lines
        assertEquals(201, lines.size());
        assertEquals(
                "strategy\tpairIndex\tkey\tbaseline\tcandidate\tdecisionType\tsegment\tcontentionBand\tbodyBand\tbaselineTransitionCount\tcandidateTransitionCount\ttransitionCountDelta\tbaselineMeanDeltaContention\tcandidateMeanDeltaContention\tmeanDeltaContentionDelta\tbaselineMeanDeltaBody\tcandidateMeanDeltaBody\tmeanDeltaBodyDelta\tbaselineMagnitude\tcandidateMagnitude\tmagnitudeDelta",
                lines.get(0));
    }

    @Test
    void testCorrelationsPearsonAndSpearmanDistinct(@TempDir Path tempDir) throws Exception {
        ComparisonResult result = createPopulatedComparisonResult();
        ComparisonExport.exportCorrelationComparisonsTsv(tempDir, result);

        Path tsv = tempDir.resolve(Constants.CORRELATION_COMPARISONS_TSV);
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);

        assertTrue(lines.size() > 1);
        assertEquals(
                "strategy\tpairIndex\tkey\tbaseline\tcandidate\tcategory\tsegment\tmethod\trowVariable\tcolumnVariable\tbaselineCorrelation\tcandidateCorrelation\tcorrelationDelta",
                lines.get(0));

        boolean foundPearson = false;
        boolean foundSpearman = false;
        for (int i = 1; i < lines.size(); i++) {
            String[] tokens = lines.get(i).split("\t");
            String method = tokens[7];
            if ("PEARSON".equals(method)) foundPearson = true;
            if ("SPEARMAN".equals(method)) foundSpearman = true;
        }

        assertTrue(foundPearson, "PEARSON rows must exist");
        assertTrue(foundSpearman, "SPEARMAN rows must exist");
    }

    @Test
    void testManifestJsonStructureAndTraceability(@TempDir Path tempDir) throws Exception {
        ComparisonResult result = createPopulatedComparisonResult();
        ComparisonExport.exportManifestJson(tempDir, result);

        Path jsonFile = tempDir.resolve(Constants.COMPARISON_MANIFEST_JSON);
        assertTrue(Files.exists(jsonFile));

        JsonNode root = MAPPER.readTree(jsonFile.toFile());
        assertEquals(2, root.get("schemaVersion").asInt());
        assertEquals("BASELINE", root.get("strategy").asText());
        assertEquals(2, root.get("pairCount").asInt());

        JsonNode pairs = root.get("pairs");
        assertEquals(2, pairs.size());
        assertEquals(
                "base-trial",
                pairs.get(0).get("baselineIdentity").get("trialId").asText());
        assertEquals("/runs/base", pairs.get(0).get("baselineSourcePath").asText());
        assertEquals(
                "cand-trial-1",
                pairs.get(0).get("candidateIdentity").get("trialId").asText());
        assertEquals("/runs/cand1", pairs.get(0).get("candidateSourcePath").asText());
        assertEquals("COMPATIBLE", pairs.get(0).get("compatibilityStatus").asText());

        JsonNode exportedArtifacts = root.get("exportedArtifacts");
        assertTrue(exportedArtifacts.size() >= 7);
    }

    @Test
    void testIncompatibleComparisonExportsIdentityAndReasons(@TempDir Path tempDir) throws Exception {
        CompletedRun baseline =
                createCompletedRun("base-trial", "Baseline Trial", "grp-1", "/runs/base", null, 1000.0, 0);
        CompletedRun incompatibleCand = createCompletedRun(
                "incompat-cand", "Incompatible Candidate", "grp-1", "/runs/incompat", null, 1000.0, 0);

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
    void testDeterministicReExportProducesIdenticalBytes(@TempDir Path tempDir1, @TempDir Path tempDir2)
            throws Exception {
        ComparisonResult result = createPopulatedComparisonResult();

        ComparisonExport.export(tempDir1, result);
        ComparisonExport.export(tempDir2, result);

        String[] filenames = {
            Constants.COMPARISON_MANIFEST_JSON,
            Constants.COMPARISON_SUMMARY_TSV,
            Constants.CONFIGURATION_DIFFERENCES_TSV,
            Constants.SCALAR_COMPARISONS_TSV,
            Constants.OCCUPANCY_COMPARISONS_TSV,
            Constants.TRANSITION_COMPARISONS_TSV,
            Constants.VECTOR_FIELD_COMPARISONS_TSV,
            Constants.CORRELATION_COMPARISONS_TSV
        };

        for (String fn : filenames) {
            byte[] b1 = Files.readAllBytes(tempDir1.resolve(fn));
            byte[] b2 = Files.readAllBytes(tempDir2.resolve(fn));
            assertArrayEquals(b1, b2, "Deterministic byte mismatch for: " + fn);

            String c1 = Files.readString(tempDir1.resolve(fn + ".sha256"), StandardCharsets.UTF_8);
            String c2 = Files.readString(tempDir2.resolve(fn + ".sha256"), StandardCharsets.UTF_8);
            assertEquals(c1, c2, "Checksum mismatch for: " + fn);
        }
    }

    @Test
    void testStringEscapingSanitizesTabsAndNewlines(@TempDir Path tempDir) throws Exception {
        CompletedRun baseline =
                createCompletedRun("base\ttab", "Baseline\nName", "grp-1", "/runs/base", null, 1000.0, 0);
        CompletedRun cand =
                createCompletedRun("cand\twith\ttabs", "Candidate\r\nName", "grp-1", "/runs/cand", null, 1100.0, 5);

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
        CompletedRun baseline =
                createCompletedRun("base-trial", "Baseline Trial", "grp-1", "/runs/base", null, 1000.0, 0);
        CompletedRun cand =
                createCompletedRun("cand-partial", "Candidate Partial", "grp-1", "/runs/cand", null, 1100.0, 5);

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
        CompletedRun baseline =
                createCompletedRun("base-trial", "Baseline Trial", "grp-1", "/runs/base", null, 1000.0, 0);
        CompletedRun cand1 = createCompletedRun("cand-mid", "Cand Mid", "grp-1", "/runs/cand1", null, 1200.0, 5);
        CompletedRun cand2 = createCompletedRun("cand-low", "Cand Low", "grp-1", "/runs/cand2", null, 900.0, 10);
        CompletedRun cand3 = createCompletedRun("cand-high", "Cand High", "grp-1", "/runs/cand3", null, 2000.0, 15);

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
        CompletedRun baseline =
                createCompletedRun("base-trial", "Baseline Trial", "grp-1", "/runs/base", null, 1000.0, 0);
        CompletedRun cand = createCompletedRun("cand-trial", "Cand Trial", "grp-1", "/runs/cand", null, 1000.0, 5);

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
        CompletedRun baseRun = createCompletedRun("base-1", "Base", "group", "path/base", null, 50000000.0, 0);
        CompletedRun candRun = createCompletedRun("cand-1", "Cand", "group", "path/cand", null, 75000000.0, 10);

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
