package calibration;

import static calibration.infra.Constants.CPU_SET_PROP;
import static calibration.infra.Constants.OUTPUT_DIRECTORY_PROP;
import static calibration.infra.Constants.REPEAT_INDEX_PROP;
import static calibration.infra.Constants.TRIAL_CONFIG_PROP;
import static calibration.infra.Constants.TRIAL_ID_PROP;
import static calibration.infra.Constants.TRIAL_INDEX_PROP;
import static calibration.infra.Constants.TRIAL_NAME_PROP;

import calibration.benchmarks.CalibrationBenchmark;
import calibration.config.HarnessConfig;
import calibration.config.HarnessConfig.ArtifactConfig;
import calibration.config.HarnessConfig.HarnessRunOptions;
import calibration.config.HarnessConfig.TrialConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CalibrationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalibrationRunner.class.getSimpleName());

    private static final String CONFIG_PATH_ARG = "config_path";
    private static final Map<String, Integer> ARGUMENTS = Map.of(CONFIG_PATH_ARG, 0);

    private static final List<String> DEFAULT_FLAGS = List.of(
            "-XX:+UseThreadPriorities",
            "--enable-native-access=ALL-UNNAMED",
            "--add-exports",
            "java.base/jdk.internal.platform=ALL-UNNAMED",
            "--add-exports",
            "java.base/jdk.internal.vm.annotation=ALL-UNNAMED");

    public static void main(String[] args) throws Exception {
        if (args.length != ARGUMENTS.size()) {
            throw new MainError("CalibrationRunner must be provided with arguments: " + ARGUMENTS.keySet());
        }

        String path = args[ARGUMENTS.get(CONFIG_PATH_ARG)];

        //noinspection JvmTaintAnalysis
        File configFile = new File(path).getCanonicalFile();

        ObjectMapper mapper = new ObjectMapper();
        HarnessConfig harnessConfig = mapper.readValue(configFile, HarnessConfig.class);

        HarnessRunOptions runOptions = harnessConfig.runOptions();
        int repeatCount = (runOptions != null && runOptions.repeatCount() != null) ? runOptions.repeatCount() : 1;
        boolean failFast = runOptions == null || runOptions.failFast() == null || runOptions.failFast();

        List<TrialConfig> activeTrials = new ArrayList<>();
        for (TrialConfig trial : harnessConfig.trials()) {
            if (!Boolean.FALSE.equals(trial.enabled())) {
                activeTrials.add(trial);
            }
        }

        boolean randomize = runOptions != null && Boolean.TRUE.equals(runOptions.randomizeTrialOrder());
        if (randomize) {
            long seed = (runOptions.randomSeed() != null) ? runOptions.randomSeed() : new Random().nextLong();
            LOGGER.info("Randomized trial order with seed: {}", seed);
            Collections.shuffle(activeTrials, new Random(seed));
        }

        HarnessConfig.ArtifactConfig artifacts = harnessConfig.artifacts();
        File baseOutputDir = null;
        if (artifacts != null
                && artifacts.outputDirectory() != null
                && !artifacts.outputDirectory().isBlank()) {
            baseOutputDir = new File(artifacts.outputDirectory()).getCanonicalFile();
            if (!baseOutputDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                baseOutputDir.mkdirs();
            }
        }

        List<String> failures = new ArrayList<>();

        for (int r = 0; r < repeatCount; r++) {
            for (int t = 0; t < activeTrials.size(); t++) {
                TrialConfig trial = activeTrials.get(t);
                if (failFast) {
                    runTrial(trial, t, r, mapper, artifacts, baseOutputDir);
                } else {
                    try {
                        runTrial(trial, t, r, mapper, artifacts, baseOutputDir);
                    } catch (Exception e) {
                        String idStr = getTrialIdentifier(trial);
                        LOGGER.error("[Failed trial] repeat={} trial={} {}", r, t, idStr, e);
                        failures.add(idStr + " (repeat " + (r + 1) + "): " + e.getMessage());
                    }
                }
            }
        }

        if (!failFast && !failures.isEmpty()) {
            throw new MainError("One or more trials failed execution: " + String.join("; ", failures));
        }
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

    private static void runTrial(
            TrialConfig trial,
            int trialIndex,
            int repeatIndex,
            ObjectMapper mapper,
            ArtifactConfig artifacts,
            File baseOutputDir)
            throws Exception {
        StringBuilder startMsg = new StringBuilder();
        startMsg.append("[Running trial] repeat=")
                .append(repeatIndex)
                .append(" trial=")
                .append(trialIndex);
        if (trial.id() != null && !trial.id().isBlank()) {
            startMsg.append(" id=").append(trial.id());
        }
        if (trial.name() != null && !trial.name().isBlank()) {
            startMsg.append(" name=").append(trial.name());
        }
        if (trial.group() != null && !trial.group().isBlank()) {
            startMsg.append(" group=").append(trial.group());
        }
        startMsg.append(" forks=")
                .append(trial.forks())
                .append(" warmups=")
                .append(trial.warmups())
                .append(" iterations=")
                .append(trial.iterations());
        LOGGER.info(startMsg.toString());

        File tempConfigFile = File.createTempFile("trial_config_", ".json");
        tempConfigFile.deleteOnExit();
        try {
            mapper.writeValue(tempConfigFile, trial);
            String canonicalPath = tempConfigFile.getCanonicalPath();

            List<String> jvmArgs = new ArrayList<>(DEFAULT_FLAGS);
            addJVMProperty(jvmArgs, TRIAL_CONFIG_PROP, canonicalPath);
            addJVMProperty(jvmArgs, TRIAL_INDEX_PROP, Integer.toString(trialIndex));
            addJVMProperty(jvmArgs, REPEAT_INDEX_PROP, Integer.toString(repeatIndex));
            addJVMProperty(jvmArgs, TRIAL_ID_PROP, trial.id());
            addJVMProperty(jvmArgs, TRIAL_NAME_PROP, trial.name());
            addJVMProperty(jvmArgs, CPU_SET_PROP);

            prepareInvocationDirectory(trial, trialIndex, repeatIndex, mapper, artifacts, baseOutputDir, jvmArgs);

            if (trial.jvmArgs() != null) {
                jvmArgs.addAll(trial.jvmArgs());
            }

            ChainedOptionsBuilder opt = new OptionsBuilder();
            opt = opt.include(CalibrationBenchmark.class.getName());
            opt = opt.jvmArgsAppend(jvmArgs.toArray(new String[0]));
            opt = opt.forks(trial.forks());
            opt = opt.warmupForks(trial.warmups());
            opt = opt.measurementIterations(trial.iterations());

            Options options = opt.build();
            new Runner(options).run();

            StringBuilder completeMsg = new StringBuilder();
            completeMsg
                    .append("[Completed trial] repeat=")
                    .append(repeatIndex)
                    .append(" trial=")
                    .append(trialIndex);
            if (trial.id() != null && !trial.id().isBlank()) {
                completeMsg.append(" id=").append(trial.id());
            }
            if (trial.name() != null && !trial.name().isBlank()) {
                completeMsg.append(" name=").append(trial.name());
            }
            LOGGER.info(completeMsg.toString());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempConfigFile.delete();
        }
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

    static File prepareInvocationDirectory(
            TrialConfig trial,
            int trialIndex,
            int repeatIndex,
            ObjectMapper mapper,
            ArtifactConfig artifacts,
            File baseOutputDir,
            List<String> jvmArgs)
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
        addJVMProperty(jvmArgs, OUTPUT_DIRECTORY_PROP, invocationDir.getCanonicalPath());
        if (artifacts != null && Boolean.TRUE.equals(artifacts.retainExpandedConfig())) {
            File expandedConfigFile = new File(invocationDir, "trial_config.json");
            mapper.writeValue(expandedConfigFile, trial);
        }
        return invocationDir;
    }

    private static final class MainError extends RuntimeException {
        public MainError(String message) {
            super(message, null, false, false);
        }
    }
}
