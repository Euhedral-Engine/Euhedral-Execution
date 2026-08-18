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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CalibrationComparison {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalibrationComparison.class);

    private CalibrationComparison() {}

    public static void runComparison(String pathOrConfig) throws Exception {
        Objects.requireNonNull(pathOrConfig, "pathOrConfig must not be null");
        ObjectMapper mapper = new ObjectMapper();

        Path path = Path.of(pathOrConfig);
        Path resolved = CompletedRunLoader.resolveRunPath(path);

        if (Files.exists(resolved) && Files.isDirectory(resolved)) {
            runExperimentComparison(resolved, null);
            return;
        }

        ComparisonConfig comparisonConfig = loadComparisonConfig(pathOrConfig, mapper);
        if (comparisonConfig.experimentDirectory() != null) {
            Path experimentDir = Path.of(comparisonConfig.experimentDirectory());
            runExperimentComparison(experimentDir, comparisonConfig);
        } else {
            runExplicitComparison(comparisonConfig);
        }
    }

    static void runExperimentComparison(@NonNull Path experimentDir, @Nullable ComparisonConfig config)
            throws Exception {
        Path resolvedExperimentDir = CompletedRunLoader.resolveRunPath(experimentDir);
        LOGGER.info("Running comparison for experiment directory: {}", resolvedExperimentDir);

        List<CompletedRun> allRuns = CompletedRunLoader.loadExperiment(resolvedExperimentDir);
        LOGGER.info("Discovered {} completed run(s) in experiment directory", allRuns.size());

        CompletedRun baselineRun = selectBaselineRun(allRuns, config != null ? config.baseline() : null);
        LOGGER.info(
                "Selected baseline run: trialId={}, sourcePath={}",
                baselineRun.identity().trialId(),
                baselineRun.identity().sourcePath());

        List<CompletedRun> candidateRuns =
                selectCandidateRuns(allRuns, baselineRun, config != null ? config.candidates() : null);
        LOGGER.info("Comparing against {} candidate run(s)...", candidateRuns.size());

        List<CandidateComparison> candidateComparisons = new ArrayList<>(candidateRuns.size());

        for (CompletedRun candidateRun : candidateRuns) {
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

        Path outputDir = (config != null
                        && config.outputDirectory() != null
                        && !config.outputDirectory().isBlank())
                ? Path.of(config.outputDirectory()).toAbsolutePath().normalize()
                : resolvedExperimentDir.resolve("comparisons").toAbsolutePath().normalize();

        LOGGER.info("Exporting comparison results to: {}", outputDir);
        ComparisonExport.export(outputDir, comparisonResult);
    }

    static void runExplicitComparison(@NonNull ComparisonConfig comparisonConfig) throws Exception {
        Objects.requireNonNull(comparisonConfig.baseline(), "baseline must not be null");
        Objects.requireNonNull(comparisonConfig.candidates(), "candidates must not be null");

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

        List<CompletedRun> candidateRuns = new ArrayList<>(candidateRefs.size() + 1);
        candidateRuns.add(baselineRun);
        for (RunReference candidateRef : candidateRefs) {
            CompletedRun candidateRun = CompletedRunLoader.load(candidateRef.path());
            if (!candidateRun
                    .identity()
                    .sourcePath()
                    .equals(baselineRun.identity().sourcePath())) {
                candidateRuns.add(candidateRun);
            }
        }

        List<CandidateComparison> candidateComparisons = new ArrayList<>(candidateRuns.size());

        for (CompletedRun candidateRun : candidateRuns) {
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

        Path outputDir = Path.of(
                        Objects.requireNonNull(comparisonConfig.outputDirectory(), "outputDirectory must not be null"))
                .toAbsolutePath()
                .normalize();
        LOGGER.info("Exporting comparison results to: {}", outputDir);
        ComparisonExport.export(outputDir, comparisonResult);
    }

    static CompletedRun selectBaselineRun(List<CompletedRun> allRuns, @Nullable RunReference explicitBaseline) {
        if (explicitBaseline != null
                && explicitBaseline.path() != null
                && !explicitBaseline.path().isBlank()) {
            String query = explicitBaseline.path().trim();
            for (CompletedRun run : allRuns) {
                if (matchesRun(run, query)) {
                    return run;
                }
            }
            try {
                return CompletedRunLoader.load(query);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Specified baseline '" + query + "' was not found among runs in experiment directory");
            }
        }

        // Automatic baseline selection:
        // 1. Run with id/name containing "baseline"
        for (CompletedRun run : allRuns) {
            if (containsIgnoreCase(run.identity().trialId(), "baseline")
                    || containsIgnoreCase(run.identity().trialName(), "baseline")
                    || containsIgnoreCase(run.identity().sourcePath(), "baseline")) {
                return run;
            }
        }

        // 2. Run with id/name containing "base"
        for (CompletedRun run : allRuns) {
            if (containsIgnoreCase(run.identity().trialId(), "base")
                    || containsIgnoreCase(run.identity().trialName(), "base")
                    || containsIgnoreCase(run.identity().sourcePath(), "base")) {
                return run;
            }
        }

        // 3. Run with origin == null (explicit non-sweep base trial)
        for (CompletedRun run : allRuns) {
            if (run.trialConfig().origin() == null) {
                return run;
            }
        }

        // 4. First run
        return allRuns.getFirst();
    }

    static List<CompletedRun> selectCandidateRuns(
            List<CompletedRun> allRuns, CompletedRun baselineRun, @Nullable List<RunReference> explicitCandidates) {
        List<CompletedRun> resolved = new ArrayList<>();
        // Baseline first so it appears at the top of the comparison data
        resolved.add(baselineRun);

        if (explicitCandidates != null && !explicitCandidates.isEmpty()) {
            for (RunReference ref : explicitCandidates) {
                String query = ref.path().trim();
                CompletedRun found = null;
                for (CompletedRun run : allRuns) {
                    if (matchesRun(run, query)) {
                        found = run;
                        break;
                    }
                }
                if (found == null) {
                    found = CompletedRunLoader.load(query);
                }
                if (!found.identity().sourcePath().equals(baselineRun.identity().sourcePath())) {
                    resolved.add(found);
                }
            }
            return List.copyOf(resolved);
        }

        // Default: all runs in the experiment
        String baselineSource = baselineRun.identity().sourcePath();
        for (CompletedRun run : allRuns) {
            if (!run.identity().sourcePath().equals(baselineSource)) {
                resolved.add(run);
            }
        }
        return List.copyOf(resolved);
    }

    private static boolean matchesRun(CompletedRun run, String query) {
        if (run.identity().trialId().equalsIgnoreCase(query)
                || (run.trialConfig().id() != null && run.trialConfig().id().equalsIgnoreCase(query))) {
            return true;
        }
        if (run.identity().trialName() != null && run.identity().trialName().equalsIgnoreCase(query)) {
            return true;
        }
        Path p = Path.of(run.identity().sourcePath());
        if (p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase(query)) {
            return true;
        }
        return run.identity().sourcePath().equalsIgnoreCase(query)
                || run.identity().sourcePath().endsWith("/" + query)
                || run.identity().sourcePath().endsWith(File.separator + query);
    }

    private static boolean containsIgnoreCase(String source, String target) {
        return source != null && source.toLowerCase().contains(target.toLowerCase());
    }

    static ComparisonConfig loadComparisonConfig(String path, ObjectMapper mapper) throws Exception {
        Path resolved = CompletedRunLoader.resolveRunPath(Path.of(path));
        File configFile = resolved.toFile();
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
