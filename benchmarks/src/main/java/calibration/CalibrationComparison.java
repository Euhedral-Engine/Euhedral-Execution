package calibration;

import calibration.comparisons.ComparisonCompatibilityAnalyzer;
import calibration.comparisons.PerformanceComparisonCalculator;
import calibration.comparisons.SystemTelemetryComparisonCalculator;
import calibration.comparisons.schema.AggregateComparison;
import calibration.comparisons.schema.CandidateComparison;
import calibration.comparisons.schema.ComparisonCompatibility;
import calibration.comparisons.schema.ComparisonResult;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.PerformanceComparison;
import calibration.comparisons.schema.RunReference;
import calibration.config.ComparisonConfig;
import calibration.io.ComparisonExport;
import calibration.io.CompletedRunLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CalibrationComparison {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalibrationComparison.class);

    static void runComparison(String configPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ComparisonConfig comparisonConfig = loadComparisonConfig(configPath, mapper);

        LOGGER.info(
                "Running comparison for baseline: {}",
                comparisonConfig.baseline().path());
        CompletedRun baselineRun =
                CompletedRunLoader.load(comparisonConfig.baseline().path());
        LOGGER.info(
                "Loaded baseline run: trialId={}, sourcePath={}",
                baselineRun.identity().trialId(),
                baselineRun.identity().sourcePath());

        List<RunReference> candidateRefs = comparisonConfig.candidates();
        LOGGER.info("Comparing against {} candidate run(s)...", candidateRefs.size());

        List<CompletedRun> candidateRuns = new ArrayList<>(candidateRefs.size());
        List<CandidateComparison> candidateComparisons = new ArrayList<>(candidateRefs.size());

        for (RunReference candidateRef : candidateRefs) {
            CompletedRun candidateRun = CompletedRunLoader.load(candidateRef.path());
            candidateRuns.add(candidateRun);

            ComparisonCompatibility compatibility = ComparisonCompatibilityAnalyzer.analyze(baselineRun, candidateRun);
            LOGGER.info(
                    "Candidate {}: compatibility={}", candidateRun.identity().trialId(), compatibility.status());

            PerformanceComparison performance =
                    PerformanceComparisonCalculator.compare(baselineRun, candidateRun, compatibility);
            AggregateComparison aggregate =
                    SystemTelemetryComparisonCalculator.compare(baselineRun, candidateRun, compatibility);

            CandidateComparison candidateComparison = new CandidateComparison(
                    baselineRun.identity(),
                    candidateRun.identity(),
                    compatibility,
                    compatibility.differences(),
                    performance,
                    List.of(),
                    aggregate);

            candidateComparisons.add(candidateComparison);
        }

        ComparisonResult comparisonResult = new ComparisonResult(baselineRun, candidateRuns, candidateComparisons);

        Path outputDir =
                Path.of(comparisonConfig.outputDirectory()).toAbsolutePath().normalize();
        LOGGER.info("Exporting comparison results to: {}", outputDir);
        ComparisonExport.export(outputDir, comparisonResult);
    }

    static ComparisonConfig loadComparisonConfig(String path, ObjectMapper mapper) throws Exception {
        File configFile = new File(path).getCanonicalFile();
        if (!configFile.exists() || !configFile.isFile()) {
            throw new IllegalArgumentException("Comparison configuration file not found: " + path);
        }
        ComparisonConfig config = mapper.readValue(configFile, ComparisonConfig.class);
        if (config == null) {
            throw new IllegalArgumentException("Comparison configuration parsed to null: " + path);
        }
        return config;
    }
}
