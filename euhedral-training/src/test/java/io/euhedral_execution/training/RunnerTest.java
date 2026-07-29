package io.euhedral_execution.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.config.ClosedLoopConfig;
import io.euhedral_execution.training.data.ClosedLoopResult;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.importer.currentworkspace.CurrentWorkspaceImportRequest;
import io.euhedral_execution.training.importer.currentworkspace.CurrentWorkspaceImportResult;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunnerTest {
    @TempDir
    Path temp;

    @Test
    void importCommandRequiresExactFlagsAndDispatchesExplicitly() throws Exception {
        Path source = temp.resolve("source");
        Path vectors = source.resolve("euhedral-training/output/temp_data");
        Files.createDirectories(vectors.getParent());
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            if (i != 0) {
                row.append(' ');
            }
            row.append(Double.doubleToRawLongBits(i / 10.0));
        }
        Files.writeString(vectors, row.append('\n'), StandardCharsets.US_ASCII);
        Path output = temp.resolve("import");
        Runner.importCurrentWorkspace(new String[]{"import-current-workspace",
                "--source-root", source.toString(), "--output", output.toString(),
                "--bootstrap-count", "1"});
        assertThat(output.resolve("COMPLETE")).isRegularFile();

        assertThatThrownBy(() -> Runner.importCurrentWorkspace(new String[]{
                "import-current-workspace", "--output", output.toString(),
                "--source-root", source.toString(), "--bootstrap-count", "1"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Runner.closedLoop(new String[]{"closed-loop"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Runner.packageRun(new String[]{"package-run"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dispatchesOnlyTheExplicitCommandAndLogsExactResults() throws Exception {
        RecordingServices services = services();
        List<String> closedLogs = captureLogs(() -> Runner.dispatch(new String[]{
                "closed-loop", "--config", temp.resolve("closed.conf").toString()},
                services));
        assertThat(services.configReads).isEqualTo(1);
        assertThat(services.closedLoopRuns).isEqualTo(1);
        assertThat(services.imports).isZero();
        assertThat(services.packageInputReads).isZero();
        assertThat(closedLogs).containsExactly(
                "stage=BOOTSTRAP_PENDING",
                "checkpoint=" + temp.resolve("checkpoint").toAbsolutePath().normalize(),
                "package=" + temp.resolve("package").toAbsolutePath().normalize(),
                "awaiting_scenario=s1-env-a-src1-core4-r1of4",
                "awaiting_scenario=s1-env-b-src4-core4-r1of1");

        Path source = temp.resolve("source");
        Files.createDirectories(source);
        Path imported = temp.resolve("imported");
        List<String> importLogs = captureLogs(() -> Runner.dispatch(new String[]{
                "import-current-workspace", "--source-root", source.toString(),
                "--output", imported.toString(), "--bootstrap-count", "7"}, services));
        assertThat(services.imports).isEqualTo(1);
        assertThat(services.lastImport.sourceRoot()).isEqualTo(source);
        assertThat(services.lastImport.outputDirectory()).isEqualTo(imported);
        assertThat(services.lastImport.bootstrapPolicyCount()).isEqualTo(7);
        assertThat(importLogs).containsExactly("output=" + imported,
                "unique_policies=9", "bootstrap_policies=7");

        Path workspace = temp.resolve("package-workspace");
        Path inputs = temp.resolve("package-inputs.properties");
        Path output = temp.resolve("package-output");
        List<String> packageLogs = captureLogs(() -> Runner.dispatch(new String[]{
                "package-run", "--workspace", workspace.toString(),
                "--inputs", inputs.toString(), "--output-root", output.toString()},
                services));
        assertThat(services.packageInputReads).isEqualTo(1);
        assertThat(services.packages).isEqualTo(1);
        assertThat(services.lastPackage.workspace()).isEqualTo(workspace);
        assertThat(services.lastPackage.outputRoot()).isEqualTo(output);
        assertThat(packageLogs).containsExactly(
                temp.resolve("published").toAbsolutePath().normalize().toString());
        assertThat(services.imports).isEqualTo(1);
    }

    @Test
    void rejectsEveryMissingDuplicateReorderedAndExtraFlagForm() throws Exception {
        RecordingServices services = services();
        List<String[]> closed = List.of(
                new String[]{"closed-loop"},
                new String[]{"closed-loop", "--config"},
                new String[]{"closed-loop", "config", "x"},
                new String[]{"closed-loop", "x", "--config"},
                new String[]{"closed-loop", "--config", "x", "extra"},
                new String[]{"closed-loop", "--config", "--config"});
        List<String[]> importer = List.of(
                new String[]{"import-current-workspace"},
                new String[]{"import-current-workspace", "--source-root", "a",
                        "--bootstrap-count", "1", "--output", "b"},
                new String[]{"import-current-workspace", "--source-root", "a",
                        "--output", "b", "--bootstrap-count", "0"},
                new String[]{"import-current-workspace", "--source-root", "a",
                        "--output", "b", "--bootstrap-count", "+1"},
                new String[]{"import-current-workspace", "--source-root", "a",
                        "--output", "b", "--bootstrap-count", "1", "extra"},
                new String[]{"import-current-workspace", "--source-root", "a",
                        "--source-root", "b", "--bootstrap-count", "1"});
        List<String[]> packages = List.of(
                new String[]{"package-run"},
                new String[]{"package-run", "--workspace", "a", "--output-root", "c",
                        "--inputs", "b"},
                new String[]{"package-run", "--workspace", "a", "--inputs", "b"},
                new String[]{"package-run", "--workspace", "a", "--inputs", "b",
                        "--output-root", "c", "extra"},
                new String[]{"package-run", "--workspace", "a", "--workspace", "b",
                        "--output-root", "c"});
        for (String[] args : concat(closed, importer, packages)) {
            assertThatThrownBy(() -> Runner.dispatch(args, services))
                    .as(String.join(" ", args))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(services.configReads).isZero();
        assertThat(services.closedLoopRuns).isZero();
        assertThat(services.imports).isZero();
        assertThat(services.packageInputReads).isZero();
        assertThat(services.packages).isZero();
    }

    @Test
    void helpLabelsVectorOnlyImportAndPackageOnlyReproduction() throws Exception {
        RecordingServices services = services();
        List<String> logs = captureLogs(() -> Runner.dispatch(new String[0], services));
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst()).contains(
                "Usage: Runner <command>",
                "closed-loop --config <path>",
                "import-current-workspace --source-root <path> --output <path>",
                "Preserve current-workspace vectors only; legacy measurements",
                "package-run --workspace <path> --inputs <path> --output-root <path>",
                "Reproduce a checkpoint-backed package; this does not rerun",
                "Legacy compatibility:");
        assertThat(services.totalCalls()).isZero();
    }

    @Test
    void stopFileBoundaryDistinguishesRegularMissingSymlinkAndIoFailure()
            throws Exception {
        Path regular = temp.resolve("STOP");
        Files.writeString(regular, "");
        assertThat(ClosedLoopRunner.stopRequested(regular)).isTrue();
        assertThat(ClosedLoopRunner.stopRequested(temp.resolve("missing"))).isFalse();
        Path symlink = temp.resolve("STOP-link");
        Files.createSymbolicLink(symlink, Path.of("STOP"));
        assertThat(ClosedLoopRunner.stopRequested(symlink)).isFalse();
        Path notDirectory = temp.resolve("not-directory");
        Files.writeString(notDirectory, "");
        assertThatThrownBy(() -> ClosedLoopRunner.stopRequested(
                notDirectory.resolve("STOP")))
                .isInstanceOf(UncheckedIOException.class);
    }

    private RecordingServices services() {
        var scenarios = new TreeSet<>(List.of(
                SourceScenario.of("env-a", 1, 4),
                SourceScenario.of("env-b", 4, 4)));
        ClosedLoopConfig config = new ClosedLoopConfig(temp.resolve("workspace"),
                "test", 1, 32, scenarios, "env-a", 2,
                0x6a09e667f3bcc909L, 131_072,
                Optional.of(temp.resolve("boot")), Optional.empty(), List.of(), Map.of(),
                "0".repeat(40), false, CandidateBudgetConfig.defaults(),
                CandidateGenerationConfig.defaults(), BenchmarkExecutionConfig.defaults(),
                AnchorSelectionConfig.defaults(), CalibrationConfig.defaults(),
                AggregationConfig.defaults(), ScenarioTrainingConfig.defaults(),
                true, temp.resolve("STOP"));
        ClosedLoopResult result = new ClosedLoopResult(
                CheckpointStage.BOOTSTRAP_PENDING, 1, temp.resolve("checkpoint"),
                Optional.empty(), Optional.empty(), scenarios,
                Optional.of(temp.resolve("package")));
        TrainingRunPackageInputs inputs = new TrainingRunPackageInputs(
                "test.partial.r00000001", "test", 1,
                0x6a09e667f3bcc909L, "0".repeat(40), false,
                BenchmarkExecutionConfig.defaults(), scenarios);
        return new RecordingServices(config, result, inputs);
    }

    private static List<String[]> concat(List<String[]> first, List<String[]> second,
            List<String[]> third) {
        ArrayList<String[]> result = new ArrayList<>(first);
        result.addAll(second);
        result.addAll(third);
        return result;
    }

    private static List<String> captureLogs(ThrowingRunnable action) throws Exception {
        Object logger = LoggerFactory.getLogger(Runner.class);
        Class<?> appenderType = Class.forName("ch.qos.logback.core.Appender");
        Class<?> listAppenderType =
                Class.forName("ch.qos.logback.core.read.ListAppender");
        Object appender = listAppenderType.getConstructor().newInstance();
        listAppenderType.getMethod("start").invoke(appender);
        logger.getClass().getMethod("addAppender", appenderType)
                .invoke(logger, appender);
        try {
            action.run();
            @SuppressWarnings("unchecked")
            List<Object> events = (List<Object>) listAppenderType.getField("list")
                    .get(appender);
            ArrayList<String> result = new ArrayList<>();
            for (Object event : events) {
                result.add((String) event.getClass()
                        .getMethod("getFormattedMessage").invoke(event));
            }
            return List.copyOf(result);
        } finally {
            logger.getClass().getMethod("detachAppender", appenderType)
                    .invoke(logger, appender);
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
        private int imports;
        private int packageInputReads;
        private int packages;
        private CurrentWorkspaceImportRequest lastImport;
        private TrainingRunPackageRequest lastPackage;

        private RecordingServices(ClosedLoopConfig config, ClosedLoopResult result,
                TrainingRunPackageInputs inputs) {
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
        public CurrentWorkspaceImportResult importWorkspace(
                CurrentWorkspaceImportRequest request) {
            imports++;
            lastImport = request;
            return new CurrentWorkspaceImportResult(request.outputDirectory(),
                    request.outputDirectory().resolve("catalog.csv"),
                    request.outputDirectory().resolve("bootstrap.csv"),
                    request.outputDirectory().resolve("report.csv"), 9,
                    request.bootstrapPolicyCount());
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
            return new TrainingRunPackage(directory, directory.resolve("manifest.json"),
                    inputs.packageId(), TrainingRunPackageStatus.PARTIAL_RECOVERABLE);
        }

        private int totalCalls() {
            return configReads + closedLoopRuns + imports + packageInputReads + packages;
        }
    }
}
