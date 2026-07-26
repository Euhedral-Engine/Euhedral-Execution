package io.euhedral_execution.training;

import io.euhedral_execution.training.networks.PolicyOrdinalNetwork;

public class Runner {

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
                    System.out.println(
                            "Closed-loop stop requested; current partial iteration was not promoted.");
                }
            }
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: Runner <command>");
        System.out.println("  merge-quantiles     Normalize and merge benchmark corpus");
        System.out.println("  merge-vectors       Deduplicate vectors from merger input");
        System.out.println("  training-info       Print DJL, CUDA, GPU, and compute-capability details");
        System.out.println("  train-vector-finder Train or generate candidates using -D properties");
        System.out.println("  benchmark [file]    Benchmark Sobol or file-backed candidates");
        System.out.println("  closed-loop         Merge -> train -> generate -> benchmark -> corpus");
    }

    private Runner() {
    }
}
