package io.euhedral_execution.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.benchmark.data.NativeBenchmarkRunPlan;
import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.config.ClosedLoopConfig;
import io.euhedral_execution.training.data.BenchmarkObservation;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.BenchmarkRunDescriptor;
import io.euhedral_execution.training.data.ClosedLoopResult;
import io.euhedral_execution.training.data.ObservationKey;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.data.enums.MeasurementEncoding;
import io.euhedral_execution.training.data.enums.ObservationStatus;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.data.io.ObservationBundleWriter;
import io.euhedral_execution.training.learning.ScenarioConditionedModel;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.inputs.ScenarioTrainingRequest;
import io.euhedral_execution.training.learning.output.ScenarioTrainingArtifacts;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.config.AnchorSelectionConfig;
import io.euhedral_execution.training.merge.config.CalibrationConfig;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.optimization.config.CandidateGenerationConfig;
import io.euhedral_execution.training.optimization.config.CmaEsConfig;
import io.euhedral_execution.training.packaging.TrainingRunPackageValidator;
import io.euhedral_execution.training.packaging.TrainingRunPackager;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageRequest;
import io.euhedral_execution.training.scheduling.config.CandidateBudgetConfig;
import io.euhedral_execution.training.scheduling.fixtures.SchedulingFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClosedLoopRunnerTest {
    @TempDir
    Path temp;

    @Test
    void bootstrapResumesAcrossRequiredEnvironmentsAndPostMergesBeforeTraining()
            throws Exception {
        Path bootstrap = temp.resolve("bootstrap.csv");
        writeBootstrap(bootstrap, 6);
        var a = SourceScenario.of("env-a", 1, 4);
        var b = SourceScenario.of("env-b", 1, 4);
        TreeSet<SourceScenario> required = new TreeSet<>(List.of(a, b));
        FakeServices firstServices = new FakeServices(false);
        ClosedLoopResult first = ClosedLoopRunner.run(config(bootstrap, required,
                "env-a", false), firstServices);
        assertThat(first.stage()).isEqualTo(
                io.euhedral_execution.training.checkpoint.enums.CheckpointStage.BOOTSTRAP_PENDING);
        assertThat(first.awaitingScenarios()).containsExactly(b);

        FakeServices secondServices = new FakeServices(true);
        ClosedLoopResult second = ClosedLoopRunner.run(config(bootstrap, required,
                "env-b", true), secondServices);
        assertThat(second.stage()).isEqualTo(
                io.euhedral_execution.training.checkpoint.enums.CheckpointStage.READY_TO_TRAIN);
        assertThat(second.latestMerge()).isPresent();
        assertThat(second.awaitingScenarios()).isEmpty();
        assertThat(secondServices.mergeCalled).isTrue();
        assertThat(second.packageDirectory()).isEmpty();

        int revision = Integer.parseInt(second.latestCheckpoint().getFileName().toString()
                .substring("checkpoint-".length()));
        ClosedLoopConfig resumed = config(bootstrap, required, "env-b", true);
        var packaged = TrainingRunPackager.publish(new TrainingRunPackageRequest(
                resumed.workspace(), temp.resolve("packages"),
                new TrainingRunPackageInputs("training.partial.r%08d".formatted(revision),
                        "training", revision, resumed.schedulerSeed(), resumed.commitSha(),
                        resumed.dirtyWorkingTree(), resumed.benchmarkConfig(), required)));
        assertThat(packaged.directory()).isDirectory();
        assertThat(packaged.directory().resolve("manifest.json")).isRegularFile();
        assertThat(packaged.directory().resolve("policy-scenario-measurements.csv"))
                .isRegularFile();
        assertThat(TrainingRunPackager.publish(new TrainingRunPackageRequest(
                resumed.workspace(), temp.resolve("packages"),
                new TrainingRunPackageInputs("training.partial.r%08d".formatted(revision),
                        "training", revision, resumed.schedulerSeed(), resumed.commitSha(),
                        resumed.dirtyWorkingTree(), resumed.benchmarkConfig(), required)))
                .directory()).isEqualTo(packaged.directory());
        var reproduced = TrainingRunPackager.publish(new TrainingRunPackageRequest(
                resumed.workspace(), temp.resolve("reproduced"),
                new TrainingRunPackageInputs("training.partial.r%08d".formatted(revision),
                        "training", revision, resumed.schedulerSeed(), resumed.commitSha(),
                        resumed.dirtyWorkingTree(), resumed.benchmarkConfig(), required)));
        assertThat(ArtifactFingerprint.sha256(reproduced.directory()))
                .isEqualTo(ArtifactFingerprint.sha256(packaged.directory()));
        assertThat(packaged.directory().resolve("vectors/robust-leaders.vectors.csv"))
                .isRegularFile();
        assertThat(packaged.directory().resolve(
                "vectors/incomplete-promising.vectors.csv")).isRegularFile();
        assertThat(packaged.directory().resolve("policy-scenario-measurements.csv"))
                .isRegularFile();
        assertThat(packaged.directory().resolve("reports/robust-ranking.md"))
                .isRegularFile();
        Path unexpected = reproduced.directory().resolve("unexpected.txt");
        Files.writeString(unexpected, "tamper\n");
        assertThatThrownBy(() -> TrainingRunPackageValidator.validate(reproduced.directory()))
                .isInstanceOf(java.io.IOException.class);
        Files.delete(unexpected);
        assertThat(TrainingRunPackageValidator.validate(reproduced.directory()).directory())
                .isEqualTo(reproduced.directory());
    }

    private ClosedLoopConfig config(Path bootstrap, TreeSet<SourceScenario> scenarios,
            String environment, boolean resume) {
        return new ClosedLoopConfig(temp.resolve("workspace"), "training", 1, 6, scenarios,
                environment, 1, 77L, 100, Optional.of(bootstrap), Optional.empty(),
                List.of(), Map.of(), "0".repeat(40), false,
                CandidateBudgetConfig.defaults(),
                new CandidateGenerationConfig(32, 8,
                        new int[]{1, 1, 1, 1, 2, 2, 3, 5, 8, 16},
                        8, 7, 1, new CmaEsConfig(false, 1, 1, 8, 0.2, 2)),
                new BenchmarkExecutionConfig(3, 100, 50, 8, 1_000, false),
                AnchorSelectionConfig.defaults(), CalibrationConfig.defaults(),
                AggregationConfig.defaults(), ScenarioTrainingConfig.defaults(), resume,
                temp.resolve("workspace/STOP"));
    }

    private static void writeBootstrap(Path file, int count) throws Exception {
        List<String> header = new ArrayList<>(List.of("schema_version",
                "bootstrap_position", "policy_id"));
        for (int i = 0; i < 28; i++) {
            header.add("weight_%02d_bits".formatted(i));
        }
        StringBuilder output = new StringBuilder(CanonicalCsv.row(header));
        for (int i = 0; i < count; i++) {
            var policy = SchedulingFixtures.policy(100 + i);
            List<String> row = new ArrayList<>(List.of("1", Integer.toString(i + 1),
                    policy.id().canonical()));
            for (double weight : policy.copyWeights()) {
                row.add("%016x".formatted(Double.doubleToRawLongBits(weight)));
            }
            output.append(CanonicalCsv.row(row));
        }
        Files.writeString(file, output, StandardCharsets.UTF_8);
    }

    private static final class FakeServices implements ClosedLoopServices {
        private final AtomicBoolean stopAfterMerge;
        boolean mergeCalled;

        private FakeServices(boolean stopAfterMerge) {
            this.stopAfterMerge = new AtomicBoolean(stopAfterMerge);
        }

        @Override
        public CalibrationPlan bootstrapCalibration(
                DataMerger.CalibrationBootstrapRequest request) throws Exception {
            return DataMerger.bootstrapCalibrationV1(request);
        }

        @Override
        public DataMerger.MergeArtifacts merge(DataMerger.MergeRequest request)
                throws Exception {
            DataMerger.MergeArtifacts result = DataMerger.mergeV1(request);
            mergeCalled = true;
            return result;
        }

        @Override
        public ScenarioTrainingArtifacts train(ScenarioTrainingRequest request) {
            throw new AssertionError("Training must not start after requested stop");
        }

        @Override
        public ScenarioConditionedModel loadAcceptedModel(Path modelDirectory,
                String producingDevice) {
            throw new AssertionError();
        }

        @Override
        public BenchmarkRunContext benchmark(NativeBenchmarkRunPlan plan,
                java.util.function.BooleanSupplier stopRequested) {
            Instant start = Instant.EPOCH;
            BenchmarkRunDescriptor descriptor = new BenchmarkRunDescriptor(1,
                    plan.benchmarkRunId(), plan.iteration(), plan.candidateCohortId(),
                    plan.scenario(), plan.commitSha(), plan.dirtyWorkingTree(),
                    EvidenceOrigin.NATIVE, start, plan.parameters());
            try (ObservationBundleWriter writer = ObservationBundleWriter.open(
                    plan.outputBundle(), descriptor)) {
                plan.policies().forEach(writer::registerPolicy);
                long offset = 0;
                for (var policy : plan.policies()) {
                    for (int repetition = 1;
                            repetition <= plan.executionConfig().expectedRepetitions();
                            repetition++) {
                        Instant observationStart = start.plusNanos(offset);
                        long elapsed = 100;
                        long frames = 100;
                        writer.write(new BenchmarkObservation(new ObservationKey(
                                plan.benchmarkRunId(), plan.scenario(), policy.policy().id(),
                                repetition), descriptor, policy, ObservationStatus.SUCCESS,
                                MeasurementEncoding.COUNTER_DERIVED, observationStart,
                                observationStart.plusNanos(elapsed), OptionalLong.of(elapsed),
                                OptionalLong.of(frames), OptionalDouble.of(
                                frames * 1_000_000_000.0 / elapsed), ""));
                        offset += elapsed;
                    }
                }
                return writer.complete(start.plusNanos(offset));
            }
        }

        @Override
        public boolean stopRequested() {
            return mergeCalled && stopAfterMerge.get();
        }

        @Override
        public int activeCoreCount() {
            return 4;
        }

        @Override
        public String activeCpuSetHex() {
            return "f";
        }
    }
}
