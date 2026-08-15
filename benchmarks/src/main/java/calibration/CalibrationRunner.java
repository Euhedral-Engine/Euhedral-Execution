package calibration;

import calibration.benchmarks.CalibrationBenchmark;
import calibration.config.HarnessConfig;
import calibration.config.HarnessConfig.HarnessRunOptions;
import calibration.config.HarnessConfig.TrialConfig;
import calibration.infra.Constants;
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

public class CalibrationRunner {
    private static final String CONFIG_PATH_ARG = "config_path";
    private static final Map<String, Integer> ARGUMENTS = Map.of(CONFIG_PATH_ARG, 0);

    private static final String TRIAL_CONFIG_PATH_PROP = Constants.TRIAL_CONFIG_PROP;
    private static final String CPU_SET_PROP = Constants.CPU_SET_PROP;

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
            System.out.println("Randomized trial order with seed: " + seed);
            Collections.shuffle(activeTrials, new Random(seed));
        }

        List<String> failures = new ArrayList<>();

        for (int r = 0; r < repeatCount; r++) {
            for (TrialConfig trial : activeTrials) {
                if (failFast) {
                    runTrial(trial, mapper);
                } else {
                    try {
                        runTrial(trial, mapper);
                    } catch (Exception e) {
                        String idStr = getTrialIdentifier(trial);
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

    private static void runTrial(TrialConfig trial, ObjectMapper mapper) throws Exception {
        File tempConfigFile = File.createTempFile("trial_config_", ".json");
        tempConfigFile.deleteOnExit();
        try {
            mapper.writeValue(tempConfigFile, trial);
            String canonicalPath = tempConfigFile.getCanonicalPath();

            List<String> jvmArgs = new ArrayList<>(DEFAULT_FLAGS);
            jvmArgs.add("-D" + TRIAL_CONFIG_PATH_PROP + "=" + canonicalPath);

            String cpuSet = System.getProperty(CPU_SET_PROP);
            if (cpuSet != null && !cpuSet.isBlank()) {
                jvmArgs.add("-D" + CPU_SET_PROP + "=" + cpuSet);
            }

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
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempConfigFile.delete();
        }
    }

    private static final class MainError extends RuntimeException {
        public MainError(String message) {
            super(message, null, false, false);
        }
    }
}
