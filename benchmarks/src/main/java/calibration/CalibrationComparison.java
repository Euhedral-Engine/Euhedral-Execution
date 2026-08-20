package calibration;

import calibration.comparisons.ComparisonCompatibilityAnalyzer;
import calibration.comparisons.ComparisonPair;
import calibration.comparisons.ComparisonPairPlan;
import calibration.comparisons.ComparisonPairPlanner;
import calibration.comparisons.PerformanceComparisonCalculator;
import calibration.comparisons.StateComparabilityCalculator;
import calibration.comparisons.SystemTelemetryComparisonCalculator;
import calibration.comparisons.schema.AggregateComparison;
import calibration.comparisons.schema.CandidateComparison;
import calibration.comparisons.schema.ComparisonCompatibility;
import calibration.comparisons.schema.ComparisonResult;
import calibration.comparisons.schema.ComparisonSet;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.PerformanceComparison;
import calibration.comparisons.schema.RunReference;
import calibration.comparisons.schema.StateComparabilityComparison;
import calibration.config.ComparisonConfig;
import calibration.config.ComparisonScope;
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

        List<CompletedRun> baselineRuns = selectBaselineRuns(allRuns, config != null ? config.baseline() : null);
        List<CompletedRun> candidateRuns =
                selectCandidateRuns(allRuns, baselineRuns, config != null ? config.candidate() : null);

        ComparisonConfig effectiveConfig =
                config != null ? config : ComparisonConfig.ofExperimentDirectory(resolvedExperimentDir.toString());

        executeComparisonPipeline(effectiveConfig, baselineRuns, candidateRuns, resolvedExperimentDir);
    }

    static void runExplicitComparison(@NonNull ComparisonConfig comparisonConfig) throws Exception {
        Objects.requireNonNull(comparisonConfig.baseline(), "baseline must not be null");
        Objects.requireNonNull(comparisonConfig.candidate(), "candidate must not be null");

        List<CompletedRun> baselineRuns = new ArrayList<>();
        for (RunReference ref : comparisonConfig.baseline().runs()) {
            loadReference(baselineRuns, ref, comparisonConfig.options().scope());
        }

        List<CompletedRun> candidateRuns = new ArrayList<>();
        for (RunReference ref : comparisonConfig.candidate().runs()) {
            loadReference(candidateRuns, ref, comparisonConfig.options().scope());
        }

        executeComparisonPipeline(comparisonConfig, baselineRuns, candidateRuns, null);
    }

    private static void loadReference(List<CompletedRun> destination, RunReference reference, ComparisonScope scope) {
        if (scope == ComparisonScope.FORK) {
            destination.addAll(CompletedRunLoader.loadForks(reference.path()));
        } else {
            destination.add(CompletedRunLoader.load(reference.path()));
        }
    }

    private static void executeComparisonPipeline(
            @NonNull ComparisonConfig config,
            @NonNull List<CompletedRun> baselineRuns,
            @NonNull List<CompletedRun> candidateRuns,
            @Nullable Path fallbackBaseDir)
            throws Exception {

        LOGGER.info(
                "Planning comparison pairs using strategy={}: {} baseline run(s), {} candidate run(s)",
                config.strategy(),
                baselineRuns.size(),
                candidateRuns.size());

        ComparisonPairPlan plan = ComparisonPairPlanner.plan(config, baselineRuns, candidateRuns);
        LOGGER.info("Constructed {} comparison pair(s)", plan.pairs().size());

        List<CandidateComparison> candidateComparisons =
                new ArrayList<>(plan.pairs().size());

        for (ComparisonPair pair : plan.pairs()) {
            CompletedRun baselineRun = pair.baseline();
            CompletedRun candidateRun = pair.candidate();

            ComparisonCompatibility compatibility = ComparisonCompatibilityAnalyzer.analyze(baselineRun, candidateRun);
            LOGGER.info(
                    "Pair #{}: {} vs {} -> compatibility={}",
                    pair.pairIndex(),
                    baselineRun.identity().trialId(),
                    candidateRun.identity().trialId(),
                    compatibility.status());

            PerformanceComparison performance =
                    PerformanceComparisonCalculator.compare(baselineRun, candidateRun, compatibility);
            AggregateComparison aggregate =
                    SystemTelemetryComparisonCalculator.compare(baselineRun, candidateRun, compatibility);
            StateComparabilityComparison stateComparability = compatibility.isComparable()
                            && baselineRun.throughput().forkScores().size() == 1
                            && candidateRun.throughput().forkScores().size() == 1
                    ? StateComparabilityCalculator.compare(baselineRun.system(), candidateRun.system())
                    : null;

            CandidateComparison candidateComparison = new CandidateComparison(
                    pair.pairIndex(),
                    baselineRun.identity(),
                    candidateRun.identity(),
                    pair.key(),
                    compatibility,
                    compatibility.differences(),
                    performance,
                    List.of(),
                    aggregate,
                    stateComparability);

            candidateComparisons.add(candidateComparison);
        }

        ComparisonResult comparisonResult = new ComparisonResult(
                plan.strategy(),
                candidateComparisons,
                plan.keyConfig(),
                plan.unmatchedBaselineKeys(),
                plan.unmatchedCandidateKeys());

        Path outputDir;
        if (config.outputDirectory() != null && !config.outputDirectory().isBlank()) {
            outputDir = Path.of(config.outputDirectory()).toAbsolutePath().normalize();
        } else if (fallbackBaseDir != null) {
            outputDir = fallbackBaseDir.resolve("comparisons").toAbsolutePath().normalize();
        } else {
            throw new IllegalArgumentException("Comparison outputDirectory must not be null");
        }

        LOGGER.info("Exporting comparison results to: {}", outputDir);
        ComparisonExport.export(outputDir, comparisonResult);
    }

    static List<CompletedRun> selectBaselineRuns(List<CompletedRun> allRuns, @Nullable ComparisonSet explicitBaseline) {
        if (explicitBaseline != null && !explicitBaseline.runs().isEmpty()) {
            List<CompletedRun> resolved = new ArrayList<>();
            for (RunReference ref : explicitBaseline.runs()) {
                String query = ref.path().trim();
                CompletedRun found = findMatchingRun(allRuns, query);
                if (found == null) {
                    found = CompletedRunLoader.load(query);
                }
                resolved.add(found);
            }
            return List.copyOf(resolved);
        }

        // Automatic single baseline selection
        return List.of(selectSingleBaseline(allRuns));
    }

    static List<CompletedRun> selectCandidateRuns(
            List<CompletedRun> allRuns, List<CompletedRun> baselineRuns, @Nullable ComparisonSet explicitCandidates) {
        if (explicitCandidates != null && !explicitCandidates.runs().isEmpty()) {
            List<CompletedRun> resolved = new ArrayList<>();
            for (RunReference ref : explicitCandidates.runs()) {
                String query = ref.path().trim();
                CompletedRun found = findMatchingRun(allRuns, query);
                if (found == null) {
                    found = CompletedRunLoader.load(query);
                }
                resolved.add(found);
            }
            return List.copyOf(resolved);
        }

        // Default: all runs not in the baseline set (or all runs if multiple baselines)
        List<CompletedRun> resolved = new ArrayList<>();
        List<String> basePaths =
                baselineRuns.stream().map(r -> r.identity().sourcePath()).toList();
        for (CompletedRun run : allRuns) {
            if (!basePaths.contains(run.identity().sourcePath())) {
                resolved.add(run);
            }
        }
        if (resolved.isEmpty()) {
            resolved.addAll(allRuns);
        }
        return List.copyOf(resolved);
    }

    private static CompletedRun selectSingleBaseline(List<CompletedRun> allRuns) {
        for (CompletedRun run : allRuns) {
            if (containsIgnoreCase(run.identity().trialId(), "baseline")
                    || containsIgnoreCase(run.identity().trialName(), "baseline")
                    || containsIgnoreCase(run.identity().sourcePath(), "baseline")) {
                return run;
            }
        }

        for (CompletedRun run : allRuns) {
            if (containsIgnoreCase(run.identity().trialId(), "base")
                    || containsIgnoreCase(run.identity().trialName(), "base")
                    || containsIgnoreCase(run.identity().sourcePath(), "base")) {
                return run;
            }
        }

        for (CompletedRun run : allRuns) {
            if (run.trialConfig().origin() == null) {
                return run;
            }
        }

        return allRuns.getFirst();
    }

    private static CompletedRun findMatchingRun(List<CompletedRun> allRuns, String query) {
        for (CompletedRun run : allRuns) {
            if (matchesRun(run, query)) {
                return run;
            }
        }
        return null;
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
