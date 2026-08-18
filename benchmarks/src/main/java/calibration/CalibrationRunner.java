package calibration;

import static calibration.infra.Constants.BENCHMARK_OUTPUT_LOG;
import static calibration.infra.Constants.OUTPUT_DIRECTORY_PROP;
import static calibration.infra.Constants.REPEAT_INDEX_PROP;
import static calibration.infra.Constants.RETAIN_OBSERVER_DATA_PROP;
import static calibration.infra.Constants.RETAIN_PER_FORK_RESULTS_PROP;
import static calibration.infra.Constants.RETAIN_PER_ITERATION_RESULTS_PROP;
import static calibration.infra.Constants.TRIAL_CONFIG_PROP;
import static calibration.infra.Constants.TRIAL_ID_PROP;
import static calibration.infra.Constants.TRIAL_INDEX_PROP;
import static calibration.infra.Constants.TRIAL_NAME_PROP;

import calibration.config.ArtifactConfig;
import calibration.config.HarnessConfig;
import calibration.config.HarnessRunOptions;
import calibration.config.ProfileLibraryLoader;
import calibration.config.SweepConfig;
import calibration.config.TrialConfig;
import calibration.config.TrialSweepExpander;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CalibrationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalibrationRunner.class.getSimpleName());

    private static final String USAGE = """
            Usage:
              euhedral-calibration run <harness-config.json>
              euhedral-calibration compare <comparison-config.json|experiment-directory>""";

    private static final List<String> DEFAULT_FLAGS = List.of(
            "-XX:+UseThreadPriorities",
            "--enable-native-access=ALL-UNNAMED",
            "--add-exports",
            "java.base/jdk.internal.platform=ALL-UNNAMED",
            "--add-exports",
            "java.base/jdk.internal.vm.annotation=ALL-UNNAMED");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw usageError();
        }

        RunnerMode mode;
        try {
            mode = RunnerMode.parse(args[0]);
        } catch (IllegalArgumentException e) {
            throw usageError();
        }

        String configPath = args[1];

        switch (mode) {
            case RUN -> runCalibration(configPath);
            case COMPARE -> CalibrationComparison.runComparison(configPath);
        }
    }

    static MainError usageError() {
        return new MainError(USAGE);
    }

    static void runCalibration(String configPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HarnessConfig harnessConfig = loadConfig(configPath, mapper);

        List<TrialConfig> activeTrials = resolveTrials(harnessConfig, mapper);
        File baseOutputDir = resolveOutputDirectory(harnessConfig.artifacts());

        runHarness(harnessConfig, activeTrials, baseOutputDir, mapper);
    }

    static HarnessConfig loadConfig(String path, ObjectMapper mapper) throws Exception {
        //noinspection JvmTaintAnalysis
        File configFile = new File(path).getCanonicalFile();
        return ProfileLibraryLoader.loadAndResolve(configFile, mapper);
    }

    static List<TrialConfig> resolveTrials(HarnessConfig harnessConfig, ObjectMapper mapper) {
        if (harnessConfig == null) {
            return List.of();
        }

        HarnessConfig resolvedConfig = harnessConfig.resolveCalibrationProfiles();
        List<TrialConfig> explicitTrials = resolvedConfig.trials();
        int explicitCount = explicitTrials.size();

        Map<String, TrialConfig> explicitTrialMap = new HashMap<>();
        for (TrialConfig trial : explicitTrials) {
            if (trial.id() != null) {
                explicitTrialMap.put(trial.id(), trial);
            }
        }

        List<SweepConfig> sweeps = resolvedConfig.sweeps() != null ? resolvedConfig.sweeps() : List.of();
        TrialSweepExpander expander = new TrialSweepExpander(mapper != null ? mapper : new ObjectMapper());

        List<TrialConfig> generatedSweepTrials = new ArrayList<>();
        int enabledSweepCount = 0;

        for (SweepConfig sweep : sweeps) {
            if (!sweep.isEnabled()) {
                continue;
            }
            enabledSweepCount++;
            TrialConfig baseTrial = explicitTrialMap.get(sweep.baseTrialId());
            if (baseTrial == null) {
                throw new IllegalArgumentException("Referenced baseTrialId '" + sweep.baseTrialId() + "' in sweep '"
                        + sweep.id() + "' was not found in trials");
            }
            List<TrialConfig> expanded = expander.expandSweep(baseTrial, sweep);
            int samplesPerCandidate = (sweep.repetitions() != null) ? sweep.repetitions() : 1;
            int totalGenerated = expanded.size();
            int uniqueCandidates = totalGenerated / samplesPerCandidate;
            LOGGER.info(
                    "Enabled sweep: sweep={}, base={}, uniqueCandidates={}, samplesPerCandidate={}, generatedTrials={}",
                    sweep.id(),
                    sweep.baseTrialId(),
                    uniqueCandidates,
                    samplesPerCandidate,
                    totalGenerated);
            generatedSweepTrials.addAll(expanded);
        }

        List<TrialConfig> resolvedTrials = new ArrayList<>();
        int enabledExplicitCount = 0;
        for (TrialConfig trial : explicitTrials) {
            if (!Boolean.FALSE.equals(trial.enabled())) {
                enabledExplicitCount++;
                resolvedTrials.add(trial);
            }
        }

        resolvedTrials.addAll(generatedSweepTrials);
        int generatedSweepCount = generatedSweepTrials.size();
        int totalResolvedCount = resolvedTrials.size();

        Set<String> seenIds = new HashSet<>();
        for (TrialConfig trial : resolvedTrials) {
            if (trial.id() != null && !trial.id().isBlank()) {
                if (!seenIds.add(trial.id())) {
                    throw new IllegalArgumentException("Duplicate trial ID found in resolved trials: " + trial.id());
                }
            }
        }

        LOGGER.info(
                "Trial resolution summary: explicitTrials={}, enabledExplicitTrials={}, enabledSweeps={}, generatedSweepTrials={}, totalResolvedTrials={}",
                explicitCount,
                enabledExplicitCount,
                enabledSweepCount,
                generatedSweepCount,
                totalResolvedCount);

        HarnessRunOptions runOptions = resolvedConfig.runOptions();
        boolean randomize = runOptions != null && Boolean.TRUE.equals(runOptions.randomizeTrialOrder());
        if (randomize) {
            long seed = (runOptions.randomSeed() != null) ? runOptions.randomSeed() : new Random().nextLong();
            LOGGER.info("Randomized trial order with seed: {}", seed);
            Collections.shuffle(resolvedTrials, new Random(seed));
        }

        return resolvedTrials;
    }

    static File resolveOutputDirectory(ArtifactConfig artifacts) throws Exception {
        if (artifacts == null
                || artifacts.outputDirectory() == null
                || artifacts.outputDirectory().isBlank()) {
            return null;
        }
        File baseOutputDir = new File(artifacts.outputDirectory()).getCanonicalFile();
        if (!baseOutputDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            baseOutputDir.mkdirs();
        }
        return baseOutputDir;
    }

    static void runHarness(
            HarnessConfig harnessConfig, List<TrialConfig> activeTrials, File baseOutputDir, ObjectMapper mapper)
            throws Exception {
        HarnessRunOptions runOptions = harnessConfig.runOptions();
        int repeatCount = (runOptions != null && runOptions.repeatCount() != null) ? runOptions.repeatCount() : 1;
        boolean failFast = runOptions == null || runOptions.failFast() == null || runOptions.failFast();

        ArtifactConfig artifacts = harnessConfig.artifacts();
        List<String> failures = new ArrayList<>();

        for (int repeat = 0; repeat < repeatCount; repeat++) {
            for (int trial = 0; trial < activeTrials.size(); trial++) {
                TrialConfig trialConfig = activeTrials.get(trial);
                if (failFast) {
                    runTrial(trialConfig, trial, repeat, mapper, artifacts, baseOutputDir);
                } else {
                    try {
                        runTrial(trialConfig, trial, repeat, mapper, artifacts, baseOutputDir);
                    } catch (Exception e) {
                        String idStr = getTrialIdentifier(trialConfig);
                        LOGGER.error("[Failed trial] repeat={} trial={} {}", repeat, trial, idStr, e);
                        failures.add(idStr + " (repeat " + (repeat + 1) + "): " + e.getMessage());
                    }
                }
            }
        }

        if (!failFast && !failures.isEmpty()) {
            throw new MainError("One or more trials failed execution: " + String.join("; ", failures));
        }
    }

    static void runTrial(
            TrialConfig trial,
            int trialIndex,
            int repeatIndex,
            ObjectMapper mapper,
            ArtifactConfig artifacts,
            File baseOutputDir)
            throws Exception {
        logTrialStart(trial, trialIndex, repeatIndex);

        File tempConfigFile = File.createTempFile("trial_config_", ".json");
        tempConfigFile.deleteOnExit();
        try {
            writeTrialConfig(mapper, tempConfigFile, trial);

            File invocationDir =
                    prepareInvocationDirectory(trial, trialIndex, repeatIndex, mapper, artifacts, baseOutputDir);
            List<String> jvmArgs = buildJvmArgs(
                    trial, trialIndex, repeatIndex, tempConfigFile.getCanonicalPath(), invocationDir, artifacts);

            Options options = buildOptions(trial, jvmArgs, artifacts, invocationDir);
            new Runner(options).run();

            logTrialCompletion(trial, trialIndex, repeatIndex);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempConfigFile.delete();
        }
    }

    static Options buildOptions(TrialConfig trial, List<String> jvmArgs, ArtifactConfig artifacts, File invocationDir) {
        ChainedOptionsBuilder opt = new OptionsBuilder();
        opt = opt.include(CalibrationBenchmark.class.getName());
        opt = opt.jvmArgsAppend(jvmArgs.toArray(new String[0]));
        opt = opt.forks(trial.forks());
        opt = opt.warmupIterations(trial.warmups());
        opt = opt.measurementIterations(trial.iterations());
        opt = opt.timeUnit(TimeUnit.SECONDS);
        if (trial.warmupTime() != null) {
            opt = opt.warmupTime(TimeValue.fromString(trial.warmupTime()));
        }
        if (trial.measurementTime() != null) {
            opt = opt.measurementTime(TimeValue.fromString(trial.measurementTime()));
        }
        if (artifacts != null && Boolean.TRUE.equals(artifacts.retainRawBenchmarkOutput()) && invocationDir != null) {
            File outputFile = new File(invocationDir, BENCHMARK_OUTPUT_LOG);
            try {
                opt = opt.output(outputFile.getCanonicalPath());
            } catch (Exception e) {
                opt = opt.output(outputFile.getAbsolutePath());
            }
        }
        return opt.build();
    }

    static void writeTrialConfig(ObjectMapper mapper, File targetFile, TrialConfig trial) throws Exception {
        mapper.writeValue(targetFile, trial);
    }

    static File prepareInvocationDirectory(
            TrialConfig trial,
            int trialIndex,
            int repeatIndex,
            ObjectMapper mapper,
            ArtifactConfig artifacts,
            File baseOutputDir)
            throws Exception {
        if (baseOutputDir == null) {
            return null;
        }
        String trialKey = (trial.id() != null && !trial.id().isBlank()) ? trial.id() : Integer.toString(trialIndex);
        File invocationDir = new File(baseOutputDir, trialKey + "_repeat_" + repeatIndex).getCanonicalFile();
        if (!invocationDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            invocationDir.mkdirs();
        }
        if (artifacts != null && Boolean.TRUE.equals(artifacts.retainExpandedConfig())) {
            writeTrialConfig(mapper, new File(invocationDir, "trial_config.json"), trial);
        }
        return invocationDir;
    }

    static List<String> buildJvmArgs(
            TrialConfig trial, int trialIndex, int repeatIndex, String canonicalConfigPath, File invocationDir) {
        return buildJvmArgs(trial, trialIndex, repeatIndex, canonicalConfigPath, invocationDir, null);
    }

    static List<String> buildJvmArgs(
            TrialConfig trial,
            int trialIndex,
            int repeatIndex,
            String canonicalConfigPath,
            File invocationDir,
            ArtifactConfig artifacts) {
        List<String> jvmArgs = new ArrayList<>(DEFAULT_FLAGS);
        addJVMProperty(jvmArgs, TRIAL_CONFIG_PROP, canonicalConfigPath);
        addJVMProperty(jvmArgs, TRIAL_INDEX_PROP, Integer.toString(trialIndex));
        addJVMProperty(jvmArgs, REPEAT_INDEX_PROP, Integer.toString(repeatIndex));
        addJVMProperty(jvmArgs, TRIAL_ID_PROP, trial.id());
        addJVMProperty(jvmArgs, TRIAL_NAME_PROP, trial.name());

        if (invocationDir != null) {
            try {
                addJVMProperty(jvmArgs, OUTPUT_DIRECTORY_PROP, invocationDir.getCanonicalPath());
            } catch (Exception e) {
                addJVMProperty(jvmArgs, OUTPUT_DIRECTORY_PROP, invocationDir.getAbsolutePath());
            }
        }

        if (artifacts != null) {
            if (artifacts.retainObserverData() != null) {
                addJVMProperty(
                        jvmArgs,
                        RETAIN_OBSERVER_DATA_PROP,
                        artifacts.retainObserverData().toString());
            }
            if (artifacts.retainPerForkResults() != null) {
                addJVMProperty(
                        jvmArgs,
                        RETAIN_PER_FORK_RESULTS_PROP,
                        artifacts.retainPerForkResults().toString());
            }
            if (artifacts.retainPerIterationResults() != null) {
                addJVMProperty(
                        jvmArgs,
                        RETAIN_PER_ITERATION_RESULTS_PROP,
                        artifacts.retainPerIterationResults().toString());
            }
        }

        if (trial.jvmArgs() != null) {
            jvmArgs.addAll(trial.jvmArgs());
        }

        return jvmArgs;
    }

    private static void logTrialStart(TrialConfig trial, int trialIndex, int repeatIndex) {
        LOGGER.info(
                "[Running trial] repeat={} trial={} id={} name={} group={} forks={} warmups={} iterations={}",
                repeatIndex,
                trialIndex,
                trial.id(),
                trial.name(),
                trial.group(),
                trial.forks(),
                trial.warmups(),
                trial.iterations());
    }

    private static void logTrialCompletion(TrialConfig trial, int trialIndex, int repeatIndex) {
        LOGGER.info(
                "[Completed trial] repeat={} trial={} id={} name={}",
                repeatIndex,
                trialIndex,
                trial.id(),
                trial.name());
    }

    private static String getTrialIdentifier(TrialConfig trial) {
        if (trial.id() != null
                && !trial.id().isBlank()
                && trial.name() != null
                && !trial.name().isBlank()) {
            return trial.id() + " (" + trial.name() + ")";
        }
        if (trial.id() != null && !trial.id().isBlank()) {
            return trial.id();
        }
        if (trial.name() != null && !trial.name().isBlank()) {
            return trial.name();
        }
        return "<unnamed trial>";
    }

    private static void addJVMProperty(List<String> jvmArgs, String property) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) {
            jvmArgs.add("-D" + property + "=" + value);
        }
    }

    private static void addJVMProperty(List<String> jvmArgs, String property, String value) {
        if (value != null && !value.isBlank()) {
            jvmArgs.add("-D" + property + "=" + value);
        }
    }

    static final class MainError extends RuntimeException {
        public MainError(String message) {
            super(message, null, false, false);
        }
    }
}
