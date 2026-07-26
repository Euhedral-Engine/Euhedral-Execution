package io.euhedral_execution.training;

import io.euhedral_execution.training.networks.PolicyOrdinalNetwork;
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
            case "merge-data", "merge-quantiles" -> DataMerger.mergeQuentiles();
            case "merge-vectors" -> DataMerger.mergeVectors();
            case "training-info" -> PolicyOrdinalNetwork.printEnvironment();
            case "train-vector-finder" -> {
                try (SequenceFinder ignored = new SequenceFinder(args)) {
                    // SequenceFinder executes the selected train or generate operation at construction.
                }
            }
            case "benchmark" -> BenchmarkRunner.run(args);
            case "closed-loop" -> {
                try {
                    ClosedLoopRunner.run(args);
                } catch (ClosedLoopRunner.StopRequested ignored) {
                    LOGGER.info(
                            "Closed-loop stop requested; current partial iteration was not promoted.");
                }
            }
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void printUsage() {
        LOGGER.info("""
                Usage: Runner <command>
                  merge-quantiles     Normalize and merge benchmark corpus
                  merge-vectors       Deduplicate vectors from merger input
                  training-info       Print DJL, CUDA, GPU, and compute-capability details
                  train-vector-finder Train or generate candidates using -D properties
                  benchmark [file]    Benchmark Sobol or file-backed candidates
                  closed-loop         Merge -> train -> generate -> benchmark -> corpus""");
    }

    private Runner() {
    }
}
