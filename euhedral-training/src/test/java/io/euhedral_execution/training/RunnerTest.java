package io.euhedral_execution.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.config.ClosedLoopConfig;
import io.euhedral_execution.training.data.ClosedLoopResult;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.config.AnchorSelectionConfig;
import io.euhedral_execution.training.merge.config.CalibrationConfig;
import io.euhedral_execution.training.optimization.config.CandidateGenerationConfig;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageRequest;
import io.euhedral_execution.training.packaging.data.TrainingRunPackage;
import io.euhedral_execution.training.packaging.enums.TrainingRunPackageStatus;
import io.euhedral_execution.training.scheduling.config.CandidateBudgetConfig;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

@Isolated
class RunnerTest {
    @TempDir
    Path temp;

    @Test
    void dispatchesOnlyTheExplicitCommandAndLogsExactResults() throws Exception {
        RecordingServices services = services();
        List<String> closedLogs = captureLogs(() -> Runner.dispatch(
                new String[] {
                    "closed-loop", "--config", temp.resolve("closed.conf").toString()
                },
                services));
        assertThat(services.configReads).isEqualTo(1);
        assertThat(services.closedLoopRuns).isEqualTo(1);
        assertThat(services.trainingDiagnostics).isZero();
        assertThat(services.packageInputReads).isZero();
        assertThat(closedLogs)
                .containsExactly(
                        "stage=BOOTSTRAP_PENDING",
                        "checkpoint="
                                + temp.resolve("checkpoint").toAbsolutePath().normalize(),
                        "package=" + temp.resolve("package").toAbsolutePath().normalize(),
                        "awaiting_scenario=s1-env-a-src1-core4-r1of4",
                        "awaiting_scenario=s1-env-b-src4-core4-r1of1");

        Runner.dispatch(new String[] {"training-info"}, services);
        assertThat(services.trainingDiagnostics).isEqualTo(1);

        Path workspace = temp.resolve("package-workspace");
        Path inputs = temp.resolve("package-inputs.properties");
        Path output = temp.resolve("package-output");
        List<String> packageLogs = captureLogs(() -> Runner.dispatch(
                new String[] {
                    "package-run",
                    "--workspace",
                    workspace.toString(),
                    "--inputs",
                    inputs.toString(),
                    "--output-root",
                    output.toString()
                },
                services));
        assertThat(services.packageInputReads).isEqualTo(1);
        assertThat(services.packages).isEqualTo(1);
        assertThat(services.lastPackage.workspace()).isEqualTo(workspace);
        assertThat(services.lastPackage.outputRoot()).isEqualTo(output);
        assertThat(packageLogs)
                .containsExactly(
                        temp.resolve("published").toAbsolutePath().normalize().toString());
        assertThat(services.trainingDiagnostics).isEqualTo(1);

        Path mergedOutput = temp.resolve("merged-plan");
        List<String> mergeLogs = captureLogs(() -> Runner.dispatch(
                new String[] {
                    "merge-calibration-plan",
                    "--workspace",
                    temp.resolve("workspace-a").toString(),
                    "--workspace",
                    temp.resolve("workspace-b").toString(),
                    "--output",
                    mergedOutput.toString()
                },
                services));
        assertThat(services.mergedCalibrationPlans).isEqualTo(1);
        assertThat(services.lastMergeWorkspaces)
                .containsExactly(temp.resolve("workspace-a"), temp.resolve("workspace-b"));
        assertThat(services.lastMergeOutput).isEqualTo(mergedOutput);
        assertThat(mergeLogs)
                .containsExactly(mergedOutput.toAbsolutePath().normalize().toString());
    }

    @Test
    void rejectsEveryMissingDuplicateReorderedAndExtraFlagForm() throws Exception {
        RecordingServices services = services();
        List<String[]> closed = List.of(
                new String[] {"closed-loop"},
                new String[] {"closed-loop", "--config"},
                new String[] {"closed-loop", "config", "x"},
                new String[] {"closed-loop", "x", "--config"},
                new String[] {"closed-loop", "--config", "x", "extra"},
                new String[] {"closed-loop", "--config", "--config"});
        List<String[]> packages = List.of(
                new String[] {"package-run"},
                new String[] {"package-run", "--workspace", "a", "--output-root", "c", "--inputs", "b"},
                new String[] {"package-run", "--workspace", "a", "--inputs", "b"},
                new String[] {"package-run", "--workspace", "a", "--inputs", "b", "--output-root", "c", "extra"},
                new String[] {"package-run", "--workspace", "a", "--workspace", "b", "--output-root", "c"});
        List<String[]> mergeCalibration = List.of(
                new String[] {"merge-calibration-plan"},
                new String[] {"merge-calibration-plan", "--workspace", "a"},
                new String[] {"merge-calibration-plan", "--output", "out"},
                new String[] {"merge-calibration-plan", "--workspace", "a", "--output"},
                new String[] {"merge-calibration-plan", "--workspace", "a", "--workspace", "--output", "out"},
                new String[] {"merge-calibration-plan", "--workspace", "a", "--output", "out", "extra"},
                new String[] {"merge-calibration-plan", "--output", "out", "--workspace", "a"});
        for (String[] args : concat(concat(closed, packages), mergeCalibration)) {
            assertThatThrownBy(() -> Runner.dispatch(args, services))
                    .as(String.join(" ", args))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(services.configReads).isZero();
        assertThat(services.closedLoopRuns).isZero();
        assertThat(services.trainingDiagnostics).isZero();
        assertThat(services.packageInputReads).isZero();
        assertThat(services.packages).isZero();
    }

    @Test
    void rejectsTrainingInfoArgumentsAndRemovedCommands() {
        RecordingServices services = services();
        assertThatThrownBy(() -> Runner.dispatch(new String[] {"training-info", "extra"}, services))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("training-info does not accept arguments");
        for (String command : List.of(
                "merge-" + "metadata",
                "merge-" + "quantiles",
                "merge-" + "vectors",
                "train-vector-" + "finder",
                "benchmark",
                "import-current-" + "workspace")) {
            assertThatThrownBy(() -> Runner.dispatch(new String[] {command}, services))
                    .as(command)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unknown command: " + command);
        }
        assertThat(services.totalCalls()).isZero();
    }

    @Test
    void helpListsOnlySupportedCommands() throws Exception {
        RecordingServices services = services();
        List<String> logs = captureLogs(() -> Runner.dispatch(new String[0], services));
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst())
                .contains(
                        "Usage: Runner <command>",
                        "closed-loop --config <path>",
                        "training-info",
                        "scenario-model DJL, PyTorch, CUDA, and device details",
                        "package-run --workspace <path> --inputs <path> --output-root <path>",
                        "merge-calibration-plan --workspace <path> [--workspace <path> ...] --output <path>",
                        "Reproduce a checkpoint-backed package; this does not rerun")
                .doesNotContain(
                        "merge-" + "metadata",
                        "merge-" + "quantiles",
                        "merge-" + "vectors",
                        "train-vector-" + "finder",
                        "benchmark [file]",
                        "import-current-" + "workspace",
                        "current-" + "workspace",
                        "Legacy compatibility");
        assertThat(services.totalCalls()).isZero();
    }

    @Test
    void stopFileBoundaryDistinguishesRegularMissingSymlinkAndIoFailure() throws Exception {
        Path regular = temp.resolve("STOP");
        Files.writeString(regular, "");
        assertThat(ClosedLoopRunner.stopRequested(regular)).isTrue();
        assertThat(ClosedLoopRunner.stopRequested(temp.resolve("missing"))).isFalse();
        Path symlink = temp.resolve("STOP-link");
        Files.createSymbolicLink(symlink, Path.of("STOP"));
        assertThat(ClosedLoopRunner.stopRequested(symlink)).isFalse();
        Path notDirectory = temp.resolve("not-directory");
        Files.writeString(notDirectory, "");
        assertThatThrownBy(() -> ClosedLoopRunner.stopRequested(notDirectory.resolve("STOP")))
                .isInstanceOf(UncheckedIOException.class);
    }

    private RecordingServices services() {
        var scenarios = new TreeSet<>(List.of(SourceScenario.of("env-a", 1, 4), SourceScenario.of("env-b", 4, 4)));
        ClosedLoopConfig config = new ClosedLoopConfig(
                temp.resolve("workspace"),
                "test",
                1,
                32,
                scenarios,
                "env-a",
                2,
                0x6a09e667f3bcc909L,
                131_072,
                Optional.of(temp.resolve("boot")),
                Optional.empty(),
                List.of(),
                Map.of(),
                "0".repeat(40),
                false,
                CandidateBudgetConfig.defaults(),
                CandidateGenerationConfig.defaults(),
                BenchmarkExecutionConfig.defaults(),
                AnchorSelectionConfig.defaults(),
                CalibrationConfig.defaults(),
                AggregationConfig.defaults(),
                ScenarioTrainingConfig.defaults(),
                true,
                temp.resolve("STOP"));
        ClosedLoopResult result = new ClosedLoopResult(
                CheckpointStage.BOOTSTRAP_PENDING,
                1,
                temp.resolve("checkpoint"),
                Optional.empty(),
                Optional.empty(),
                scenarios,
                Optional.of(temp.resolve("package")));
        TrainingRunPackageInputs inputs = new TrainingRunPackageInputs(
                "test.partial.r00000001",
                "test",
                1,
                0x6a09e667f3bcc909L,
                "0".repeat(40),
                false,
                BenchmarkExecutionConfig.defaults(),
                scenarios);
        return new RecordingServices(config, result, inputs);
    }

    private static List<String[]> concat(List<String[]> first, List<String[]> second) {
        ArrayList<String[]> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static List<String> captureLogs(ThrowingRunnable action) throws Exception {
        Object logger = LoggerFactory.getLogger(Runner.class);
        Class<?> appenderType = Class.forName("ch.qos.logback.core.Appender");
        Class<?> listAppenderType = Class.forName("ch.qos.logback.core.read.ListAppender");
        Object appender = listAppenderType.getConstructor().newInstance();
        listAppenderType.getMethod("start").invoke(appender);
        logger.getClass().getMethod("addAppender", appenderType).invoke(logger, appender);
        try {
            action.run();
            @SuppressWarnings("unchecked")
            List<Object> events =
                    (List<Object>) listAppenderType.getField("list").get(appender);
            ArrayList<String> result = new ArrayList<>();
            for (Object event : events) {
                result.add((String)
                        event.getClass().getMethod("getFormattedMessage").invoke(event));
            }
            return List.copyOf(result);
        } finally {
            logger.getClass().getMethod("detachAppender", appenderType).invoke(logger, appender);
            listAppenderType.getMethod("stop").invoke(appender);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private final class RecordingServices implements Runner.CommandServices {
        private final ClosedLoopConfig config;
        private final ClosedLoopResult result;
        private final TrainingRunPackageInputs inputs;
        private int configReads;
        private int closedLoopRuns;
        private int trainingDiagnostics;
        private int packageInputReads;
        private int packages;
        private int mergedCalibrationPlans;
        private TrainingRunPackageRequest lastPackage;
        private List<Path> lastMergeWorkspaces = List.of();
        private Path lastMergeOutput;

        private RecordingServices(ClosedLoopConfig config, ClosedLoopResult result, TrainingRunPackageInputs inputs) {
            this.config = config;
            this.result = result;
            this.inputs = inputs;
        }

        @Override
        public ClosedLoopConfig readConfig(Path path) {
            configReads++;
            return config;
        }

        @Override
        public ClosedLoopResult runClosedLoop(ClosedLoopConfig ignored) {
            closedLoopRuns++;
            return result;
        }

        @Override
        public void printTrainingEnvironment() {
            trainingDiagnostics++;
        }

        @Override
        public TrainingRunPackageInputs readPackageInputs(Path path) {
            packageInputReads++;
            return inputs;
        }

        @Override
        public TrainingRunPackage publishPackage(TrainingRunPackageRequest request) {
            packages++;
            lastPackage = request;
            Path directory = temp.resolve("published");
            return new TrainingRunPackage(
                    directory,
                    directory.resolve("manifest.json"),
                    inputs.packageId(),
                    TrainingRunPackageStatus.PARTIAL_RECOVERABLE);
        }

        @Override
        public Path mergeCalibrationPlans(List<Path> workspaces, Path outputDirectory) {
            mergedCalibrationPlans++;
            lastMergeWorkspaces = List.copyOf(workspaces);
            lastMergeOutput = outputDirectory;
            return outputDirectory;
        }

        private int totalCalls() {
            return configReads
                    + closedLoopRuns
                    + trainingDiagnostics
                    + packageInputReads
                    + packages
                    + mergedCalibrationPlans;
        }
    }
}
