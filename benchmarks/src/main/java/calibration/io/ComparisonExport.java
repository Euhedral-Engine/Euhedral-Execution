package calibration.io;

import calibration.comparisons.ComparisonKey;
import calibration.comparisons.schema.CandidateComparison;
import calibration.comparisons.schema.ComparisonManifest;
import calibration.comparisons.schema.ComparisonManifest.ComparisonPairManifestEntry;
import calibration.comparisons.schema.ComparisonResult;
import calibration.comparisons.schema.ConfigurationDifference;
import calibration.comparisons.schema.PerformanceComparison;
import calibration.infra.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Exports fork-throughput comparisons and the configuration differences that qualify them.
public final class ComparisonExport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final List<String> EXPORTED_ARTIFACTS = List.of(
            Constants.COMPARISON_MANIFEST_JSON,
            Constants.COMPARISON_SUMMARY_TSV,
            Constants.CONFIGURATION_DIFFERENCES_TSV);

    private ComparisonExport() {}

    public static void export(@NonNull Path outputDir, @NonNull ComparisonResult result) throws Exception {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Files.createDirectories(outputDir);
        exportManifestJson(outputDir, result);
        exportComparisonSummaryTsv(outputDir, result);
        exportConfigurationDifferencesTsv(outputDir, result);
    }

    public static void exportManifestJson(@NonNull Path outputDir, @NonNull ComparisonResult result) throws Exception {
        List<ComparisonPairManifestEntry> pairs =
                new ArrayList<>(result.comparisons().size());
        for (CandidateComparison comparison : result.comparisons()) {
            String key = comparison.comparisonKey() == null
                    ? null
                    : comparison.comparisonKey().format();
            pairs.add(new ComparisonPairManifestEntry(
                    comparison.pairIndex(), key, comparison.baseline(), comparison.candidate()));
        }
        ComparisonManifest manifest = new ComparisonManifest(
                ComparisonManifest.CURRENT_SCHEMA_VERSION,
                result.strategy(),
                result.keyConfig() == null ? null : result.keyConfig().paths(),
                pairs.size(),
                pairs,
                result.unmatchedBaselineKeys().stream()
                        .map(ComparisonKey::format)
                        .toList(),
                result.unmatchedCandidateKeys().stream()
                        .map(ComparisonKey::format)
                        .toList(),
                EXPORTED_ARTIFACTS);
        Path file = outputDir.resolve(Constants.COMPARISON_MANIFEST_JSON);
        OBJECT_MAPPER.writeValue(file.toFile(), manifest);
        TrialExport.writeChecksum(file);
    }

    public static void exportComparisonSummaryTsv(@NonNull Path outputDir, @NonNull ComparisonResult result)
            throws Exception {
        Path file = outputDir.resolve(Constants.COMPARISON_SUMMARY_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "strategy\tpairIndex\tkey\tbaseline\tcandidate\tbaselineMean\tcandidateMean\tunit\tabsoluteDelta\trelativeDeltaPercent\tbaselineVariance\tcandidateVariance\tbaselineStdDev\tcandidateStdDev\tbaselineCv\tcandidateCv\tbaselineForkCount\tcandidateForkCount\toutcome\n");
            for (CandidateComparison comparison : result.comparisons()) {
                PerformanceComparison performance = comparison.performance();
                writer.write(result.strategy().name());
                writer.write("\t" + comparison.pairIndex());
                writer.write("\t"
                        + sanitize(
                                comparison.comparisonKey() == null
                                        ? ""
                                        : comparison.comparisonKey().format()));
                writer.write("\t" + sanitize(comparison.baseline().trialId()));
                writer.write("\t" + sanitize(comparison.candidate().trialId()));
                writer.write("\t" + format(performance.baselineForkSummary().mean()));
                writer.write("\t" + format(performance.candidateForkSummary().mean()));
                writer.write("\t" + sanitize(performance.baseline().scoreUnit()));
                writer.write("\t" + format(performance.absoluteDelta()));
                writer.write("\t" + format(performance.relativeDeltaPercent()));
                writer.write("\t" + format(performance.baselineForkSummary().variance()));
                writer.write("\t" + format(performance.candidateForkSummary().variance()));
                writer.write("\t" + format(performance.baselineForkSummary().standardDeviation()));
                writer.write("\t" + format(performance.candidateForkSummary().standardDeviation()));
                writer.write("\t" + format(performance.baselineForkSummary().coefficientOfVariation()));
                writer.write("\t" + format(performance.candidateForkSummary().coefficientOfVariation()));
                writer.write("\t" + performance.baselineForkSummary().count());
                writer.write("\t" + performance.candidateForkSummary().count());
                writer.write("\t" + performance.outcome().name() + "\n");
            }
        }
        TrialExport.writeChecksum(file);
    }

    public static void exportConfigurationDifferencesTsv(@NonNull Path outputDir, @NonNull ComparisonResult result)
            throws Exception {
        Path file = outputDir.resolve(Constants.CONFIGURATION_DIFFERENCES_TSV);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(
                    "strategy\tpairIndex\tkey\tbaseline\tcandidate\tcategory\tpath\tbaselineValue\tcandidateValue\n");
            for (CandidateComparison comparison : result.comparisons()) {
                for (ConfigurationDifference difference : comparison.configurationDifferences()) {
                    writer.write(result.strategy().name() + "\t"
                            + comparison.pairIndex() + "\t"
                            + sanitize(
                                    comparison.comparisonKey() == null
                                            ? ""
                                            : comparison.comparisonKey().format())
                            + "\t" + sanitize(comparison.baseline().trialId())
                            + "\t" + sanitize(comparison.candidate().trialId())
                            + "\t" + difference.category().name()
                            + "\t" + sanitize(difference.path())
                            + "\t" + format(difference.baselineValue())
                            + "\t" + format(difference.candidateValue()) + "\n");
                }
            }
        }
        TrialExport.writeChecksum(file);
    }

    private static String format(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        return sanitize(value.isTextual() ? value.textValue() : value.toString());
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
