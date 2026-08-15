package calibration;

import calibration.benchmarks.CalibrationBenchmark;
import calibration.config.HarnessConfig;
import calibration.config.HarnessConfig.TrialConfig;
import calibration.infra.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class CalibrationRunner {
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
        for (TrialConfig trial : harnessConfig.trials()) {
            runTrial(trial);
        }
    }

    private static void runTrial(TrialConfig trial) throws Exception {
        List<String> jvmArgs = new ArrayList<>(DEFAULT_FLAGS);
        if (trial.jvmArgs() != null) {
            jvmArgs.addAll(trial.jvmArgs());
        }
        jvmArgs.add(String.format("-D%s=\"%s\"", Constants.TRIAL_CONFIG_PROP, "insert_path_here"));

        ChainedOptionsBuilder opt = new OptionsBuilder();
        opt = opt.include(CalibrationBenchmark.class.getName());
        opt = opt.jvmArgsAppend(jvmArgs.toArray(new String[0]));
        opt = opt.forks(trial.forks());
        opt = opt.warmupForks(trial.warmups());
        opt = opt.measurementIterations(trial.iterations());

        Options options = opt.build();
        new Runner(options).run();
    }

    private static final class MainError extends RuntimeException {
        public MainError(String message) {
            super(message, null, false, false);
        }
    }
}
