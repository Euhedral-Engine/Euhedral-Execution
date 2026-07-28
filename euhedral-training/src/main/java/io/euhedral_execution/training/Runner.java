package io.euhedral_execution.training;

import io.euhedral_execution.training.importer.currentworkspace.CurrentWorkspaceImportRequest;
import io.euhedral_execution.training.importer.currentworkspace.CurrentWorkspaceImportResult;
import io.euhedral_execution.training.importer.currentworkspace.CurrentWorkspaceImporter;
import io.euhedral_execution.training.legacy.PooledBenchmarkRunner;
import io.euhedral_execution.training.legacy.PooledSequenceFinder;
import io.euhedral_execution.training.networks.PolicyOrdinalNetwork;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageRequest;
import io.euhedral_execution.training.packaging.data.TrainingRunPackage;
import io.euhedral_execution.training.packaging.io.TrainingRunPackageInputsCodec;
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
            case "closed-loop" -> closedLoop(args);
            // TEMPORARY_CURRENT_WORKSPACE_IMPORT_REMOVAL
            case "import-current-workspace" -> importCurrentWorkspace(args);
            case "package-run" -> packageRun(args);
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    static void closedLoop(String[] args) throws Exception {
        if (args.length != 3 || !args[1].equals("--config")) {
            throw new IllegalArgumentException(
                    "closed-loop requires --config <path> in that order");
        }
        ClosedLoopResult result = ClosedLoopRunner.run(
                ClosedLoopConfigCodec.read(Path.of(args[2])));
        LOGGER.info("stage={}", result.stage());
        LOGGER.info("checkpoint={}", result.latestCheckpoint().toAbsolutePath().normalize());
        LOGGER.info("package={}", result.packageDirectory().orElseThrow()
                .toAbsolutePath().normalize());
        result.awaitingScenarios().forEach(scenario ->
                LOGGER.info("awaiting_scenario={}", scenario.canonical()));
    }

    // TEMPORARY_CURRENT_WORKSPACE_IMPORT_REMOVAL
    static void importCurrentWorkspace(String[] args) throws Exception {
        if (args.length != 7 || !args[1].equals("--source-root")
                || !args[3].equals("--output") || !args[5].equals("--bootstrap-count")) {
            throw new IllegalArgumentException("import-current-workspace requires "
                    + "--source-root <path> --output <path> --bootstrap-count <count> "
                    + "in that order");
        }
        int bootstrapCount;
        try {
            if (!args[6].matches("[1-9][0-9]*")) {
                throw new NumberFormatException();
            }
            bootstrapCount = Integer.parseInt(args[6]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Bootstrap count must be a positive integer",
                    error);
        }
        CurrentWorkspaceImportResult result = CurrentWorkspaceImporter.importWorkspace(
                new CurrentWorkspaceImportRequest(Path.of(args[2]), Path.of(args[4]),
                        bootstrapCount));
        LOGGER.info("output={}", result.directory());
        LOGGER.info("unique_policies={}", result.uniquePolicyCount());
        LOGGER.info("bootstrap_policies={}", result.bootstrapPolicyCount());
    }

    static void packageRun(String[] args) throws Exception {
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
                  closed-loop --config <path>
                                      Run the typed closed loop; no -Dcycle.* properties are read.
                                      run.resume controls resume, run.stop_file requests a
                                      checkpoint-safe stop, and the package path is printed.
                  import-current-workspace --source-root <path> --output <path> \
                --bootstrap-count <count>
                                      Preserve current-workspace vectors only; legacy measurements
                                      are rejected. Use bootstrap-policies.vectors.csv as
                                      run.bootstrap_policies.
                  package-run --workspace <path> --inputs <path> --output-root <path>
                                      Reproduce a checkpoint-backed package; this does not rerun
                                      the physical benchmark.

                  Legacy compatibility:
                  merge-quantiles     Normalize and merge pooled benchmark corpus
                  merge-vectors       Deduplicate pooled vectors from merger input
                  training-info       Print DJL, CUDA, GPU, and compute-capability details
                  train-vector-finder Train or generate candidates using -D properties
                  benchmark [file]    Benchmark Sobol or file-backed candidates
                """);
    }

    private Runner() {
    }
}
