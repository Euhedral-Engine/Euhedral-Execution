package io.euhedral_execution.training;

import io.euhedral_execution.training.networks.PolicyOrdinalNetwork;
import io.euhedral_execution.training.legacy.PooledBenchmarkRunner;
import io.euhedral_execution.training.legacy.PooledSequenceFinder;
import io.euhedral_execution.training.packaging.TrainingRunPackage;
import io.euhedral_execution.training.packaging.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.TrainingRunPackageInputsCodec;
import io.euhedral_execution.training.packaging.TrainingRunPackageRequest;
import io.euhedral_execution.training.packaging.TrainingRunPackager;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Runner {

    private static final Logger LOGGER = LoggerFactory.getLogger(Runner.class);

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        switch (args[0]) {
            case "merge-metadata", "merge-quantiles" -> DataMerger.mergeQuentiles();
            case "merge-vectors" -> DataMerger.mergeVectors();
            case "training-info" -> PolicyOrdinalNetwork.printEnvironment();
            case "train-vector-finder" -> {
                try (PooledSequenceFinder ignored = new PooledSequenceFinder()) {
                    // SequenceFinder executes the selected train or generate operation at construction.
                }
            }
            case "benchmark" -> PooledBenchmarkRunner.run(args);
            case "closed-loop" -> {
                try {
                    ClosedLoopRunner.run();
                } catch (ClosedLoopRunner.StopRequested ignored) {
                    LOGGER.info(
                            "Closed-loop stop requested; current partial iteration was not promoted.");
                }
            }
            case "package-run" -> packageRun(args);
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void packageRun(String[] args) throws Exception {
        if (args.length != 7 || !args[1].equals("--workspace")
                || !args[3].equals("--inputs") || !args[5].equals("--output-root")) {
            throw new IllegalArgumentException("package-run requires --workspace <path> "
                    + "--inputs <path> --output-root <path> in that order");
        }
        Path workspace = Path.of(args[2]);
        Path inputPath = Path.of(args[4]);
        Path outputRoot = Path.of(args[6]);
        TrainingRunPackageInputs inputs = TrainingRunPackageInputsCodec.read(inputPath);
        TrainingRunPackage result = TrainingRunPackager.publish(
                new TrainingRunPackageRequest(workspace, outputRoot, inputs));
        LOGGER.info("{}", result.directory().toAbsolutePath().normalize());
    }

    private static void printUsage() {
        LOGGER.info("""
                Usage: Runner <command>
                  merge-quantiles     Normalize and merge benchmark corpus
                  merge-vectors       Deduplicate vectors from merger input
                  training-info       Print DJL, CUDA, GPU, and compute-capability details
                  train-vector-finder Train or generate candidates using -D properties
                  benchmark [file]    Benchmark Sobol or file-backed candidates
                  closed-loop         Merge -> train -> generate -> benchmark -> package
                  package-run --workspace <path> --inputs <path> --output-root <path>
                                      Reproduce one checkpoint-backed package""");
    }

    private Runner() {
    }
}
