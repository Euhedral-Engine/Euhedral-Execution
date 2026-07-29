package io.euhedral_execution.training;

import io.euhedral_execution.training.config.ClosedLoopConfig;
import io.euhedral_execution.training.config.ClosedLoopConfigCodec;
import io.euhedral_execution.training.data.ClosedLoopResult;
import io.euhedral_execution.training.learning.TrainingEnvironment;
import io.euhedral_execution.training.packaging.TrainingRunPackager;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageRequest;
import io.euhedral_execution.training.packaging.data.TrainingRunPackage;
import io.euhedral_execution.training.packaging.io.TrainingRunPackageInputsCodec;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Runner {

    private static final Logger LOGGER = LoggerFactory.getLogger(Runner.class);

    public static void main(String[] args) throws Exception {
        dispatch(args, ProductionCommandServices.INSTANCE);
    }

    static void dispatch(String[] args, CommandServices services) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        switch (args[0]) {
            case "closed-loop" -> closedLoop(args, services);
            case "training-info" -> trainingInfo(args, services);
            case "package-run" -> packageRun(args, services);
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    static void closedLoop(String[] args) throws Exception {
        closedLoop(args, ProductionCommandServices.INSTANCE);
    }

    private static void closedLoop(String[] args, CommandServices services) throws Exception {
        if (args.length != 3 || !args[1].equals("--config")
                || args[2].startsWith("--")) {
            throw new IllegalArgumentException(
                    "closed-loop requires --config <path> in that order");
        }
        ClosedLoopResult result = services.runClosedLoop(
                services.readConfig(Path.of(args[2])));
        LOGGER.info("stage={}", result.stage());
        LOGGER.info("checkpoint={}", result.latestCheckpoint().toAbsolutePath().normalize());
        LOGGER.info("package={}", result.packageDirectory().orElseThrow()
                .toAbsolutePath().normalize());
        result.awaitingScenarios().forEach(scenario ->
                LOGGER.info("awaiting_scenario={}", scenario.canonical()));
    }

    private static void trainingInfo(String[] args, CommandServices services) {
        if (args.length != 1) {
            throw new IllegalArgumentException("training-info does not accept arguments");
        }
        services.printTrainingEnvironment();
    }

    static void packageRun(String[] args) throws Exception {
        packageRun(args, ProductionCommandServices.INSTANCE);
    }

    private static void packageRun(String[] args, CommandServices services) throws Exception {
        if (args.length != 7 || !args[1].equals("--workspace")
                || !args[3].equals("--inputs") || !args[5].equals("--output-root")
                || args[2].startsWith("--") || args[4].startsWith("--")
                || args[6].startsWith("--")) {
            throw new IllegalArgumentException("package-run requires --workspace <path> "
                    + "--inputs <path> --output-root <path> in that order");
        }
        Path workspace = Path.of(args[2]);
        Path inputPath = Path.of(args[4]);
        Path outputRoot = Path.of(args[6]);
        TrainingRunPackageInputs inputs = services.readPackageInputs(inputPath);
        TrainingRunPackage result = services.publishPackage(
                new TrainingRunPackageRequest(workspace, outputRoot, inputs));
        LOGGER.info("{}", result.directory().toAbsolutePath().normalize());
    }

    private static void printUsage() {
        LOGGER.info("""
                Usage: Runner <command>
                  closed-loop --config <path>
                                      Run the typed closed loop; no -Dcycle.* properties are read.
                                      run.resume controls resume, run.stop_file requests a
                                      checkpoint-safe stop, and the package path is printed.
                  training-info       Print scenario-model DJL, PyTorch, CUDA, and device details;
                                      this does not train or benchmark.
                  package-run --workspace <path> --inputs <path> --output-root <path>
                                      Reproduce a checkpoint-backed package; this does not rerun
                                      the physical benchmark.
                """);
    }

    interface CommandServices {
        ClosedLoopConfig readConfig(Path path) throws Exception;

        ClosedLoopResult runClosedLoop(ClosedLoopConfig config) throws Exception;

        void printTrainingEnvironment();

        TrainingRunPackageInputs readPackageInputs(Path path) throws Exception;

        TrainingRunPackage publishPackage(TrainingRunPackageRequest request) throws Exception;
    }

    private enum ProductionCommandServices implements CommandServices {
        INSTANCE;

        @Override
        public ClosedLoopConfig readConfig(Path path) throws Exception {
            return ClosedLoopConfigCodec.read(path);
        }

        @Override
        public ClosedLoopResult runClosedLoop(ClosedLoopConfig config) throws Exception {
            return ClosedLoopRunner.run(config);
        }

        @Override
        public void printTrainingEnvironment() {
            TrainingEnvironment.print();
        }

        @Override
        public TrainingRunPackageInputs readPackageInputs(Path path) throws Exception {
            return TrainingRunPackageInputsCodec.read(path);
        }

        @Override
        public TrainingRunPackage publishPackage(TrainingRunPackageRequest request)
                throws Exception {
            return TrainingRunPackager.publish(request);
        }
    }

    private Runner() {
    }
}
