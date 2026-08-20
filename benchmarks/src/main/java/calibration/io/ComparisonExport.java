package calibration.io;

import calibration.comparisons.ComparisonKey;
import calibration.comparisons.schema.CandidateComparison;
import calibration.comparisons.schema.ComparisonCompatibility;
import calibration.comparisons.schema.ComparisonManifest;
import calibration.comparisons.schema.ComparisonManifest.ComparisonPairManifestEntry;
import calibration.comparisons.schema.ComparisonResult;
import calibration.comparisons.schema.ConfigurationDifference;
import calibration.comparisons.schema.CoreComparison;
import calibration.comparisons.schema.CorrelationComparison;
import calibration.comparisons.schema.OccupancyComparison;
import calibration.comparisons.schema.PerformanceComparison;
import calibration.comparisons.schema.RunArtifacts;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ScalarComparison;
import calibration.comparisons.schema.TransitionComparison;
import calibration.comparisons.schema.VectorCellComparison;
import calibration.comparisons.schema.VectorFieldComparison;
import calibration.infra.Constants;
import calibration.statistics.Band;
import calibration.statistics.VectorCell;
import calibration.statistics.iteration.BranchOccupancyResult;
import calibration.statistics.iteration.CorrelationResult;
import calibration.statistics.iteration.TransitionAnalysis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Exporter for calibration comparison results into deterministic TSV artifacts, JSON manifest, and SHA-256 checksums.
public final class ComparisonExport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String[] DECISION_TYPES = {"idle", "exec"};
    private static final String[] SEGMENTS_2 = {"head", "steady_state"};
    private static final String[] CORRELATION_METHODS = {"PEARSON", "SPEARMAN"};

    private static final List<String> EXPORTED_ARTIFACT_FILENAMES = List.of(
            Constants.COMPARISON_MANIFEST_JSON,
            Constants.COMPARISON_SUMMARY_TSV,
            Constants.CONFIGURATION_DIFFERENCES_TSV,
            Constants.SCALAR_COMPARISONS_TSV,
            Constants.OCCUPANCY_COMPARISONS_TSV,
            Constants.TRANSITION_COMPARISONS_TSV,
            Constants.VECTOR_FIELD_COMPARISONS_TSV,
            Constants.CORRELATION_COMPARISONS_TSV);

    private ComparisonExport() {}

    /// Exports all comparison artifacts (manifest and TSVs) along with SHA-256 checksums to outputDirectory.
    public static void export(@NonNull Path outputDir, @NonNull ComparisonResult result) throws Exception {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Files.createDirectories(outputDir);
        exportManifestJson(outputDir, result);
        exportComparisonSummaryTsv(outputDir, result);
        exportConfigurationDifferencesTsv(outputDir, result);
        exportScalarComparisonsTsv(outputDir, result);
        exportOccupancyComparisonsTsv(outputDir, result);
        exportTransitionComparisonsTsv(outputDir, result);
        exportVectorFieldComparisonsTsv(outputDir, result);
        exportCorrelationComparisonsTsv(outputDir, result);
    }

    /// Alias for export.
    public static void exportAll(@NonNull Path outputDir, @NonNull ComparisonResult result) throws Exception {
        export(outputDir, result);
    }

    /// Exports the comparison manifest JSON and SHA-256 checksum.
    public static void exportManifestJson(@NonNull Path outputDir, @NonNull ComparisonResult result) throws Exception {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.COMPARISON_MANIFEST_JSON);

        List<ComparisonPairManifestEntry> pairEntries =
                new ArrayList<>(result.comparisons().size());
        for (CandidateComparison comp : result.comparisons()) {
            RunIdentity baseId = comp.baseline();
            RunIdentity candId = comp.candidate();
            ComparisonCompatibility compat = comp.compatibility();
            RunArtifacts baseArtifacts = RunArtifacts.standard(baseId.sourcePath());
            RunArtifacts candArtifacts = RunArtifacts.standard(candId.sourcePath());
            String keyStr = comp.comparisonKey() != null ? comp.comparisonKey().format() : null;

            pairEntries.add(new ComparisonPairManifestEntry(
                    comp.pairIndex(),
                    keyStr,
                    baseId,
                    baseId.sourcePath(),
                    baseArtifacts,
                    candId,
                    candId.sourcePath(),
                    candArtifacts,
                    compat.status(),
                    compat.reasons()));
        }

        List<String> keyPaths = result.keyConfig() != null ? result.keyConfig().paths() : null;
        List<String> unmatchedBase = result.unmatchedBaselineKeys().stream()
                .map(ComparisonKey::format)
                .toList();
        List<String> unmatchedCand = result.unmatchedCandidateKeys().stream()
                .map(ComparisonKey::format)
                .toList();

        ComparisonManifest manifest = new ComparisonManifest(
                ComparisonManifest.CURRENT_SCHEMA_VERSION,
                result.strategy(),
                keyPaths,
                pairEntries.size(),
                pairEntries,
                unmatchedBase,
                unmatchedCand,
                EXPORTED_ARTIFACT_FILENAMES);

        OBJECT_MAPPER.writeValue(file.toFile(), manifest);
        TrialExport.writeChecksum(file);
    }

    /// Exports authoritative throughput performance comparison summary to TSV.
    public static void exportComparisonSummaryTsv(@NonNull Path outputDir, @NonNull ComparisonResult result)
            throws Exception {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.COMPARISON_SUMMARY_TSV);
        String strategyStr = result.strategy().name();

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "strategy\tpairIndex\tkey\tbaseline\tcandidate\tcompatibilityStatus\tbaselineMean\tcandidateMean\tunit\tabsoluteDelta\trelativeDeltaPercent\tbaselineVariance\tcandidateVariance\tbaselineStdDev\tcandidateStdDev\tbaselineCv\tcandidateCv\tbaselineForkCount\tcandidateForkCount\toutcome\n");
            for (CandidateComparison comp : result.comparisons()) {
                if (comp == null) {
                    continue;
                }
                String pairIndexStr = Integer.toString(comp.pairIndex());
                String keyStr = comp.comparisonKey() != null
                        ? sanitizeString(comp.comparisonKey().format())
                        : "";
                String baseId = sanitizeString(comp.baseline().trialId());
                String candId = sanitizeString(comp.candidate().trialId());
                String status = comp.compatibility().status().name();
                PerformanceComparison perf = comp.performance();
                boolean isBaselineSelf = comp.baseline()
                                .trialId()
                                .equals(comp.candidate().trialId())
                        && comp.baseline().sourcePath().equals(comp.candidate().sourcePath());
                if (perf != null) {
                    double absDelta = isBaselineSelf ? 0.0 : perf.absoluteDelta();
                    double relDelta = isBaselineSelf ? 0.0 : perf.relativeDeltaPercent();
                    String outcome =
                            isBaselineSelf ? "BASELINE" : perf.outcome().name();
                    writer.write(strategyStr + "\t"
                            + pairIndexStr + "\t"
                            + keyStr + "\t"
                            + baseId + "\t"
                            + candId + "\t"
                            + status + "\t"
                            + formatDouble(perf.baselineForkSummary().mean()) + "\t"
                            + formatDouble(perf.candidateForkSummary().mean()) + "\t"
                            + sanitizeString(perf.baseline().scoreUnit()) + "\t"
                            + formatDouble(absDelta) + "\t"
                            + formatDouble(relDelta) + "\t"
                            + formatDouble(perf.baselineForkSummary().variance()) + "\t"
                            + formatDouble(perf.candidateForkSummary().variance()) + "\t"
                            + formatDouble(perf.baselineForkSummary().standardDeviation()) + "\t"
                            + formatDouble(perf.candidateForkSummary().standardDeviation()) + "\t"
                            + formatDouble(perf.baselineForkSummary().coefficientOfVariation()) + "\t"
                            + formatDouble(perf.candidateForkSummary().coefficientOfVariation()) + "\t"
                            + perf.baselineForkSummary().count() + "\t"
                            + perf.candidateForkSummary().count() + "\t"
                            + outcome + "\n");
                } else {
                    String outcome = isBaselineSelf ? "BASELINE" : "UNAVAILABLE";
                    writer.write(strategyStr + "\t"
                            + pairIndexStr + "\t"
                            + keyStr + "\t"
                            + baseId + "\t"
                            + candId + "\t"
                            + status + "\t"
                            + formatDouble(Double.NaN) + "\t"
                            + formatDouble(Double.NaN) + "\t"
                            + "" + "\t"
                            + formatDouble(Double.NaN) + "\t"
                            + formatDouble(Double.NaN) + "\t"
                            + formatDouble(Double.NaN) + "\t"
                            + formatDouble(Double.NaN) + "\t"
                            + formatDouble(Double.NaN) + "\t"
                            + formatDouble(Double.NaN) + "\t"
                            + formatDouble(Double.NaN) + "\t"
                            + formatDouble(Double.NaN) + "\t"
                            + 0 + "\t"
                            + 0 + "\t"
                            + outcome + "\n");
                }
            }
        }
        TrialExport.writeChecksum(file);
    }

    /// Exports structural configuration differences to TSV.
    public static void exportConfigurationDifferencesTsv(@NonNull Path outputDir, @NonNull ComparisonResult result)
            throws Exception {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.CONFIGURATION_DIFFERENCES_TSV);
        String strategyStr = result.strategy().name();

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "strategy\tpairIndex\tkey\tbaseline\tcandidate\tcompatibilityStatus\tcategory\tpath\tbaselineValue\tcandidateValue\n");
            for (CandidateComparison comp : result.comparisons()) {
                if (comp == null) {
                    continue;
                }
                String pairIndexStr = Integer.toString(comp.pairIndex());
                String keyStr = comp.comparisonKey() != null
                        ? sanitizeString(comp.comparisonKey().format())
                        : "";
                String baseId = sanitizeString(comp.baseline().trialId());
                String candId = sanitizeString(comp.candidate().trialId());
                String status = comp.compatibility().status().name();

                List<ConfigurationDifference> diffs = new ArrayList<>(comp.configurationDifferences());
                diffs.sort(Comparator.comparing(ConfigurationDifference::path));

                for (ConfigurationDifference diff : diffs) {
                    if (diff == null) {
                        continue;
                    }
                    writer.write(strategyStr + "\t"
                            + pairIndexStr + "\t"
                            + keyStr + "\t"
                            + baseId + "\t"
                            + candId + "\t"
                            + status + "\t"
                            + diff.category().name() + "\t"
                            + sanitizeString(diff.path()) + "\t"
                            + formatJsonNode(diff.baselineValue()) + "\t"
                            + formatJsonNode(diff.candidateValue()) + "\n");
                }
            }
        }
        TrialExport.writeChecksum(file);
    }

    /// Exports continuous scalar comparisons to TSV.
    public static void exportScalarComparisonsTsv(@NonNull Path outputDir, @NonNull ComparisonResult result)
            throws Exception {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.SCALAR_COMPARISONS_TSV);
        String strategyStr = result.strategy().name();

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "strategy\tpairIndex\tkey\tbaseline\tcandidate\tscope\tcategory\tsegment\tmetric\tbaselineCount\tcandidateCount\tbaselineMean\tcandidateMean\tmeanDelta\tbaselineMedian\tcandidateMedian\tmedianDelta\tbaselineVariance\tcandidateVariance\tvarianceDelta\tbaselineStdDev\tcandidateStdDev\tstdDevDelta\tbaselineCv\tcandidateCv\tcvDelta\tbaselineP25\tcandidateP25\tp25Delta\tbaselineP50\tcandidateP50\tp50Delta\tbaselineP75\tcandidateP75\tp75Delta\tbaselineP95\tcandidateP95\tp95Delta\tbaselineIqr\tcandidateIqr\tiqrDelta\tbaselineNormalizedIqr\tcandidateNormalizedIqr\tnormalizedIqrDelta\tbaselineP95ToP50\tcandidateP95ToP50\tp95ToP50Delta\n");
            for (CandidateComparison comp : result.comparisons()) {
                if (comp == null) {
                    continue;
                }
                String pairIndexStr = Integer.toString(comp.pairIndex());
                String keyStr = comp.comparisonKey() != null
                        ? sanitizeString(comp.comparisonKey().format())
                        : "";
                String baseId = sanitizeString(comp.baseline().trialId());
                String candId = sanitizeString(comp.candidate().trialId());

                if (comp.aggregate() != null) {
                    for (Map.Entry<String, ScalarComparison> entry :
                            comp.aggregate().scalarComparisons().entrySet()) {
                        String key = entry.getKey();
                        ScalarComparison sc = entry.getValue();
                        if (sc == null) {
                            continue;
                        }
                        String[] parts = key.split("\\.", 3);
                        String category = parts.length > 0 ? normalizeCategory(parts[0]) : "";
                        String segment = parts.length > 1 ? normalizeSegment(parts[1]) : "";
                        String metric = parts.length > 2 ? normalizeMetric(parts[2]) : "";
                        writeScalarComparisonRow(
                                writer,
                                strategyStr,
                                pairIndexStr,
                                keyStr,
                                baseId,
                                candId,
                                "SYSTEM",
                                category,
                                segment,
                                metric,
                                sc);
                    }
                }

                for (CoreComparison coreComp : comp.cores()) {
                    if (coreComp == null) {
                        continue;
                    }
                    String coreScope = "CORE_" + coreComp.core();
                    for (Map.Entry<String, ScalarComparison> entry :
                            coreComp.scalarComparisons().entrySet()) {
                        String key = entry.getKey();
                        ScalarComparison sc = entry.getValue();
                        if (sc == null) {
                            continue;
                        }
                        String[] parts = key.split("\\.", 3);
                        String category = parts.length > 0 ? normalizeCategory(parts[0]) : "";
                        String segment = parts.length > 1 ? normalizeSegment(parts[1]) : "";
                        String metric = parts.length > 2 ? normalizeMetric(parts[2]) : "";
                        writeScalarComparisonRow(
                                writer,
                                strategyStr,
                                pairIndexStr,
                                keyStr,
                                baseId,
                                candId,
                                coreScope,
                                category,
                                segment,
                                metric,
                                sc);
                    }
                }
            }
        }
        TrialExport.writeChecksum(file);
    }

    private static void writeScalarComparisonRow(
            BufferedWriter writer,
            String strategy,
            String pairIndex,
            String comparisonKey,
            String baseId,
            String candId,
            String scope,
            String category,
            String segment,
            String metric,
            ScalarComparison sc)
            throws Exception {
        writer.write(strategy + "\t"
                + pairIndex + "\t"
                + comparisonKey + "\t"
                + baseId + "\t"
                + candId + "\t"
                + scope + "\t"
                + category + "\t"
                + segment + "\t"
                + metric + "\t"
                + sc.baseline().count() + "\t"
                + sc.candidate().count() + "\t"
                + formatDouble(sc.baseline().mean()) + "\t"
                + formatDouble(sc.candidate().mean()) + "\t"
                + formatDouble(sc.meanDelta()) + "\t"
                + formatDouble(sc.baseline().median()) + "\t"
                + formatDouble(sc.candidate().median()) + "\t"
                + formatDouble(sc.medianDelta()) + "\t"
                + formatDouble(sc.baseline().variance()) + "\t"
                + formatDouble(sc.candidate().variance()) + "\t"
                + formatDouble(sc.varianceDelta()) + "\t"
                + formatDouble(sc.baseline().standardDeviation()) + "\t"
                + formatDouble(sc.candidate().standardDeviation()) + "\t"
                + formatDouble(sc.standardDeviationDelta()) + "\t"
                + formatDouble(sc.baseline().coefficientOfVariation()) + "\t"
                + formatDouble(sc.candidate().coefficientOfVariation()) + "\t"
                + formatDouble(sc.cvDelta()) + "\t"
                + formatDouble(sc.baseline().p25()) + "\t"
                + formatDouble(sc.candidate().p25()) + "\t"
                + formatDouble(sc.p25Delta()) + "\t"
                + formatDouble(sc.baseline().p50()) + "\t"
                + formatDouble(sc.candidate().p50()) + "\t"
                + formatDouble(sc.p50Delta()) + "\t"
                + formatDouble(sc.baseline().p75()) + "\t"
                + formatDouble(sc.candidate().p75()) + "\t"
                + formatDouble(sc.p75Delta()) + "\t"
                + formatDouble(sc.baseline().p95()) + "\t"
                + formatDouble(sc.candidate().p95()) + "\t"
                + formatDouble(sc.p95Delta()) + "\t"
                + formatDouble(sc.baseline().iqr()) + "\t"
                + formatDouble(sc.candidate().iqr()) + "\t"
                + formatDouble(sc.iqrDelta()) + "\t"
                + formatDouble(sc.baseline().normalizedIqr()) + "\t"
                + formatDouble(sc.candidate().normalizedIqr()) + "\t"
                + formatDouble(sc.normalizedIqrDelta()) + "\t"
                + formatDouble(sc.baseline().p95ToP50Ratio()) + "\t"
                + formatDouble(sc.candidate().p95ToP50Ratio()) + "\t"
                + formatDouble(sc.p95ToP50RatioDelta()) + "\n");
    }

    /// Exports 5x5 branch occupancy comparisons to TSV.
    public static void exportOccupancyComparisonsTsv(@NonNull Path outputDir, @NonNull ComparisonResult result)
            throws Exception {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.OCCUPANCY_COMPARISONS_TSV);
        String strategyStr = result.strategy().name();

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "strategy\tpairIndex\tkey\tbaseline\tcandidate\tdecisionType\tcontentionBand\tbodyBand\tbaselineCount\tcandidateCount\tcountDelta\tbaselineProbability\tcandidateProbability\tprobabilityDelta\tbaselineContentionCentroid\tcandidateContentionCentroid\tcontentionCentroidDelta\tbaselineBodyCentroid\tcandidateBodyCentroid\tbodyCentroidDelta\tcentroidDistance\tbaselineContentionVariance\tcandidateContentionVariance\tcontentionVarianceDelta\tbaselineBodyVariance\tcandidateBodyVariance\tbodyVarianceDelta\tbaselineCovariance\tcandidateCovariance\tcovarianceDelta\tbaselineRadius\tcandidateRadius\tradiusDelta\ttotalVariationDistance\n");
            for (CandidateComparison comp : result.comparisons()) {
                if (comp == null || comp.aggregate() == null) {
                    continue;
                }
                String pairIndexStr = Integer.toString(comp.pairIndex());
                String keyStr = comp.comparisonKey() != null
                        ? sanitizeString(comp.comparisonKey().format())
                        : "";
                String baseId = sanitizeString(comp.baseline().trialId());
                String candId = sanitizeString(comp.candidate().trialId());

                for (String dt : DECISION_TYPES) {
                    OccupancyComparison occ = "idle".equals(dt)
                            ? comp.aggregate().idleOccupancy()
                            : comp.aggregate().execOccupancy();
                    if (occ == null) {
                        continue;
                    }
                    BranchOccupancyResult base = occ.baseline();
                    BranchOccupancyResult cand = occ.candidate();
                    long[][] baseCounts = base.exactCounts();
                    long[][] candCounts = cand.exactCounts();
                    long[][] countDeltas = occ.countDeltas();
                    double[][] baseProbs = base.normalizedOccupancy();
                    double[][] candProbs = cand.normalizedOccupancy();
                    double[][] probDeltas = occ.probabilityDeltas();

                    for (int c = 0; c < Band.GRID_SIZE; c++) {
                        for (int b = 0; b < Band.GRID_SIZE; b++) {
                            writer.write(strategyStr + "\t"
                                    + pairIndexStr + "\t"
                                    + keyStr + "\t"
                                    + baseId + "\t"
                                    + candId + "\t"
                                    + dt + "\t"
                                    + c + "\t"
                                    + b + "\t"
                                    + baseCounts[c][b] + "\t"
                                    + candCounts[c][b] + "\t"
                                    + countDeltas[c][b] + "\t"
                                    + formatDouble(baseProbs[c][b]) + "\t"
                                    + formatDouble(candProbs[c][b]) + "\t"
                                    + formatDouble(probDeltas[c][b]) + "\t"
                                    + formatDouble(base.contentionCentroid()) + "\t"
                                    + formatDouble(cand.contentionCentroid()) + "\t"
                                    + formatDouble(occ.contentionCentroidDelta()) + "\t"
                                    + formatDouble(base.bodyCentroid()) + "\t"
                                    + formatDouble(cand.bodyCentroid()) + "\t"
                                    + formatDouble(occ.bodyCentroidDelta()) + "\t"
                                    + formatDouble(occ.centroidDistance()) + "\t"
                                    + formatDouble(base.contentionVariance()) + "\t"
                                    + formatDouble(cand.contentionVariance()) + "\t"
                                    + formatDouble(occ.contentionVarianceDelta()) + "\t"
                                    + formatDouble(base.bodyVariance()) + "\t"
                                    + formatDouble(cand.bodyVariance()) + "\t"
                                    + formatDouble(occ.bodyVarianceDelta()) + "\t"
                                    + formatDouble(base.contentionBodyCovariance()) + "\t"
                                    + formatDouble(cand.contentionBodyCovariance()) + "\t"
                                    + formatDouble(occ.covarianceDelta()) + "\t"
                                    + formatDouble(base.radius()) + "\t"
                                    + formatDouble(cand.radius()) + "\t"
                                    + formatDouble(occ.radiusDelta()) + "\t"
                                    + formatDouble(occ.totalVariationDistance()) + "\n");
                        }
                    }
                }
            }
        }
        TrialExport.writeChecksum(file);
    }

    /// Exports 25-state Markov transition comparisons to TSV.
    public static void exportTransitionComparisonsTsv(@NonNull Path outputDir, @NonNull ComparisonResult result)
            throws Exception {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.TRANSITION_COMPARISONS_TSV);
        String strategyStr = result.strategy().name();

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "strategy\tpairIndex\tkey\tbaseline\tcandidate\tdecisionType\tsegment\tfromState\tfromContention\tfromBody\ttoState\ttoContention\ttoBody\tbaselineCount\tcandidateCount\tcountDelta\tbaselineProbability\tcandidateProbability\tprobabilityDelta\tbaselineSelfTransitionRate\tcandidateSelfTransitionRate\tselfTransitionRateDelta\tbaselineDominantOutgoingState\tcandidateDominantOutgoingState\tdominantStateChanged\tbaselineDominantProbability\tcandidateDominantProbability\tdominantProbabilityDelta\n");
            for (CandidateComparison comp : result.comparisons()) {
                if (comp == null || comp.aggregate() == null) {
                    continue;
                }
                String pairIndexStr = Integer.toString(comp.pairIndex());
                String keyStr = comp.comparisonKey() != null
                        ? sanitizeString(comp.comparisonKey().format())
                        : "";
                String baseId = sanitizeString(comp.baseline().trialId());
                String candId = sanitizeString(comp.candidate().trialId());

                for (String dt : DECISION_TYPES) {
                    for (String seg : SEGMENTS_2) {
                        TransitionComparison tc =
                                switch (dt + "_" + seg) {
                                    case "idle_head" -> comp.aggregate().idleHeadTransitions();
                                    case "idle_steady_state" -> comp.aggregate().idleSteadyStateTransitions();
                                    case "exec_head" -> comp.aggregate().execHeadTransitions();
                                    default -> comp.aggregate().execSteadyStateTransitions();
                                };
                        if (tc == null) {
                            continue;
                        }
                        TransitionAnalysis base = tc.baseline();
                        TransitionAnalysis cand = tc.candidate();
                        long[][] baseCounts = base.transitionCounts();
                        long[][] candCounts = cand.transitionCounts();
                        long[][] countDeltas = tc.countDeltas();
                        double[][] baseProbs = base.transitionProbabilities();
                        double[][] candProbs = cand.transitionProbabilities();
                        double[][] probDeltas = tc.probabilityDeltas();
                        double[] selfRateDeltas = tc.selfTransitionRateDeltas();
                        int[] candDominantStates = tc.candidateDominantOutgoingStates();
                        double[] domProbDeltas = tc.dominantOutgoingProbabilityDeltas();

                        for (int from = 0; from < Band.TOTAL_STATES; from++) {
                            int fromC = TransitionAnalysis.contentionBandOf(from);
                            int fromB = TransitionAnalysis.bodyBandOf(from);
                            double baseSelf = base.selfTransitionRate(from);
                            double candSelf = cand.selfTransitionRate(from);
                            double selfDelta = selfRateDeltas[from];
                            int baseDom = base.dominantOutgoingState(from);
                            int candDom = candDominantStates[from];
                            boolean domChanged = baseDom != candDom;
                            double baseDomProb = base.dominantOutgoingProbability(from);
                            double candDomProb = cand.dominantOutgoingProbability(from);
                            double domProbDelta = domProbDeltas[from];

                            for (int to = 0; to < Band.TOTAL_STATES; to++) {
                                int toC = TransitionAnalysis.contentionBandOf(to);
                                int toB = TransitionAnalysis.bodyBandOf(to);
                                long bCount = baseCounts[from][to];
                                long cCount = candCounts[from][to];
                                long cDelta = countDeltas[from][to];
                                double bProb = baseProbs[from][to];
                                double cProb = candProbs[from][to];
                                double pDelta = probDeltas[from][to];

                                writer.write(strategyStr + "\t"
                                        + pairIndexStr + "\t"
                                        + keyStr + "\t"
                                        + baseId + "\t"
                                        + candId + "\t"
                                        + dt + "\t"
                                        + seg + "\t"
                                        + from + "\t"
                                        + fromC + "\t"
                                        + fromB + "\t"
                                        + to + "\t"
                                        + toC + "\t"
                                        + toB + "\t"
                                        + bCount + "\t"
                                        + cCount + "\t"
                                        + cDelta + "\t"
                                        + formatDouble(bProb) + "\t"
                                        + formatDouble(cProb) + "\t"
                                        + formatDouble(pDelta) + "\t"
                                        + formatDouble(baseSelf) + "\t"
                                        + formatDouble(candSelf) + "\t"
                                        + formatDouble(selfDelta) + "\t"
                                        + baseDom + "\t"
                                        + candDom + "\t"
                                        + domChanged + "\t"
                                        + formatDouble(baseDomProb) + "\t"
                                        + formatDouble(candDomProb) + "\t"
                                        + formatDouble(domProbDelta) + "\n");
                            }
                        }
                    }
                }
            }
        }
        TrialExport.writeChecksum(file);
    }

    /// Exports 5x5 displacement vector field comparisons to TSV.
    public static void exportVectorFieldComparisonsTsv(@NonNull Path outputDir, @NonNull ComparisonResult result)
            throws Exception {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.VECTOR_FIELD_COMPARISONS_TSV);
        String strategyStr = result.strategy().name();

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "strategy\tpairIndex\tkey\tbaseline\tcandidate\tdecisionType\tsegment\tcontentionBand\tbodyBand\tbaselineTransitionCount\tcandidateTransitionCount\ttransitionCountDelta\tbaselineMeanDeltaContention\tcandidateMeanDeltaContention\tmeanDeltaContentionDelta\tbaselineMeanDeltaBody\tcandidateMeanDeltaBody\tmeanDeltaBodyDelta\tbaselineMagnitude\tcandidateMagnitude\tmagnitudeDelta\n");
            for (CandidateComparison comp : result.comparisons()) {
                if (comp == null || comp.aggregate() == null) {
                    continue;
                }
                String pairIndexStr = Integer.toString(comp.pairIndex());
                String keyStr = comp.comparisonKey() != null
                        ? sanitizeString(comp.comparisonKey().format())
                        : "";
                String baseId = sanitizeString(comp.baseline().trialId());
                String candId = sanitizeString(comp.candidate().trialId());

                for (String dt : DECISION_TYPES) {
                    for (String seg : SEGMENTS_2) {
                        VectorFieldComparison vfc =
                                switch (dt + "_" + seg) {
                                    case "idle_head" -> comp.aggregate().idleHeadVectorField();
                                    case "idle_steady_state" -> comp.aggregate().idleSteadyStateVectorField();
                                    case "exec_head" -> comp.aggregate().execHeadVectorField();
                                    default -> comp.aggregate().execSteadyStateVectorField();
                                };
                        if (vfc == null) {
                            continue;
                        }
                        for (int c = 0; c < Band.GRID_SIZE; c++) {
                            for (int b = 0; b < Band.GRID_SIZE; b++) {
                                VectorCellComparison cell = vfc.cell(c, b);
                                VectorCell baseCell = cell.baseline();
                                VectorCell candCell = cell.candidate();

                                writer.write(strategyStr + "\t"
                                        + pairIndexStr + "\t"
                                        + keyStr + "\t"
                                        + baseId + "\t"
                                        + candId + "\t"
                                        + dt + "\t"
                                        + seg + "\t"
                                        + c + "\t"
                                        + b + "\t"
                                        + baseCell.transitionCount() + "\t"
                                        + candCell.transitionCount() + "\t"
                                        + cell.transitionCountDelta() + "\t"
                                        + formatDouble(baseCell.meanDeltaContention()) + "\t"
                                        + formatDouble(candCell.meanDeltaContention()) + "\t"
                                        + formatDouble(cell.meanDeltaContentionDelta()) + "\t"
                                        + formatDouble(baseCell.meanDeltaBody()) + "\t"
                                        + formatDouble(candCell.meanDeltaBody()) + "\t"
                                        + formatDouble(cell.meanDeltaBodyDelta()) + "\t"
                                        + formatDouble(baseCell.magnitude()) + "\t"
                                        + formatDouble(candCell.magnitude()) + "\t"
                                        + formatDouble(cell.magnitudeDelta()) + "\n");
                            }
                        }
                    }
                }
            }
        }
        TrialExport.writeChecksum(file);
    }

    /// Exports aligned correlation matrix comparisons to TSV.
    public static void exportCorrelationComparisonsTsv(@NonNull Path outputDir, @NonNull ComparisonResult result)
            throws Exception {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(Constants.CORRELATION_COMPARISONS_TSV);
        String strategyStr = result.strategy().name();

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "strategy\tpairIndex\tkey\tbaseline\tcandidate\tcategory\tsegment\tmethod\trowVariable\tcolumnVariable\tbaselineCorrelation\tcandidateCorrelation\tcorrelationDelta\n");
            for (CandidateComparison comp : result.comparisons()) {
                if (comp == null || comp.aggregate() == null) {
                    continue;
                }
                String pairIndexStr = Integer.toString(comp.pairIndex());
                String keyStr = comp.comparisonKey() != null
                        ? sanitizeString(comp.comparisonKey().format())
                        : "";
                String baseId = sanitizeString(comp.baseline().trialId());
                String candId = sanitizeString(comp.candidate().trialId());

                for (Map.Entry<String, CorrelationComparison> entry :
                        comp.aggregate().correlationComparisons().entrySet()) {
                    String key = entry.getKey();
                    CorrelationComparison cc = entry.getValue();
                    if (cc == null) {
                        continue;
                    }
                    String[] parts = key.split("\\.", 2);
                    String category = parts.length > 0 ? normalizeCategory(parts[0]) : "";
                    String segment = parts.length > 1 ? normalizeSegment(parts[1]) : "";

                    String[] cols = cc.columnNames();
                    CorrelationResult base = cc.baseline();
                    CorrelationResult cand = cc.candidate();
                    double[][] baseP = base.pearsonMatrix();
                    double[][] candP = cand.pearsonMatrix();
                    double[][] pDeltas = cc.pearsonDeltas();
                    double[][] baseS = base.spearmanMatrix();
                    double[][] candS = cand.spearmanMatrix();
                    double[][] sDeltas = cc.spearmanDeltas();

                    for (String method : CORRELATION_METHODS) {
                        for (int i = 0; i < cols.length; i++) {
                            for (int j = 0; j < cols.length; j++) {
                                double baseVal;
                                double candVal;
                                double deltaVal;
                                if ("PEARSON".equals(method)) {
                                    baseVal = (i < baseP.length && j < baseP[i].length) ? baseP[i][j] : Double.NaN;
                                    candVal = (i < candP.length && j < candP[i].length) ? candP[i][j] : Double.NaN;
                                    deltaVal =
                                            (i < pDeltas.length && j < pDeltas[i].length) ? pDeltas[i][j] : Double.NaN;
                                } else {
                                    baseVal = (i < baseS.length && j < baseS[i].length) ? baseS[i][j] : Double.NaN;
                                    candVal = (i < candS.length && j < candS[i].length) ? candS[i][j] : Double.NaN;
                                    deltaVal =
                                            (i < sDeltas.length && j < sDeltas[i].length) ? sDeltas[i][j] : Double.NaN;
                                }
                                writer.write(strategyStr + "\t"
                                        + pairIndexStr + "\t"
                                        + keyStr + "\t"
                                        + baseId + "\t"
                                        + candId + "\t"
                                        + category + "\t"
                                        + segment + "\t"
                                        + method + "\t"
                                        + sanitizeString(cols[i]) + "\t"
                                        + sanitizeString(cols[j]) + "\t"
                                        + formatDouble(baseVal) + "\t"
                                        + formatDouble(candVal) + "\t"
                                        + formatDouble(deltaVal) + "\n");
                            }
                        }
                    }
                }
            }
        }
        TrialExport.writeChecksum(file);
    }

    private static String formatJsonNode(@Nullable JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isValueNode()) {
            return sanitizeString(node.asText());
        }
        return sanitizeString(node.toString());
    }

    private static String sanitizeString(@Nullable String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String normalizeCategory(String cat) {
        return switch (cat) {
            case "cycleStart" -> "cycle_start";
            case "batchProgress" -> "batch_progress";
            case "batchComplete" -> "batch_complete";
            case "rawBodyCost" -> "raw_body_cost";
            case "idleDecisions", "idleDecision" -> "idle_decision";
            case "execDecisions", "execDecision" -> "exec_decision";
            default -> toSnakeCase(cat);
        };
    }

    private static String normalizeSegment(String seg) {
        return switch (seg) {
            case "steadyState" -> "steady_state";
            default -> seg;
        };
    }

    private static String normalizeMetric(String metric) {
        return switch (metric) {
            case "batchSize" -> "batch_size";
            case "upstreamCount" -> "upstream_count";
            case "registeredWorkers" -> "registered_workers";
            case "productiveHandleCount" -> "productive_handle_count";
            case "productiveHandleRatio" -> "productive_handle_ratio";
            case "workerRank" -> "worker_rank";
            case "avgServiceTime" -> "avg_service_time";
            case "smoothedBodyCost" -> "smoothed_body_cost";
            default -> toSnakeCase(metric);
        };
    }

    private static String toSnakeCase(String camel) {
        if (camel == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /// Formats a double value with its full decimal representation (without scientific notation).
    public static String formatDouble(double val) {
        if (Double.isNaN(val)) {
            return "NaN";
        }
        if (Double.isInfinite(val)) {
            return val > 0.0 ? "Infinity" : "-Infinity";
        }
        return BigDecimal.valueOf(val).toPlainString();
    }
}
