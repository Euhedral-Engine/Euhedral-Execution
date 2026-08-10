package io.euhedral_execution.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import io.euhedral_execution.training.learning.InsufficientScenarioLearningDataException;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.inputs.ScenarioTrainingRequest;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.config.AnchorSelectionConfig;
import io.euhedral_execution.training.merge.config.CalibrationConfig;
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
import org.mockito.ArgumentCaptor;

class ClosedLoopRunnerTest {
    @TempDir
    Path temp;

    private static void writeBootstrap(Path file, int count) throws Exception {
        List<String> header = new ArrayList<>(List.of("schema_version", "bootstrap_position", "policy_id"));
        for (int i = 0; i < 28; i++) {
            header.add("weight_%02d_bits".formatted(i));
        }
        StringBuilder output = new StringBuilder(CanonicalCsv.row(header));
        for (int i = 0; i < count; i++) {
            var policy = SchedulingFixtures.policy(100 + i);
            List<String> row = new ArrayList<>(
                    List.of("1", Integer.toString(i + 1), policy.id().canonical()));
            for (double weight : policy.copyWeights()) {
                row.add("%016x".formatted(Double.doubleToRawLongBits(weight)));
            }
            output.append(CanonicalCsv.row(row));
        }
        Files.writeString(file, output, StandardCharsets.UTF_8);
    }

    private static ScenarioTrainingConfig withBatchSize(ScenarioTrainingConfig source, int batchSize) {
        return new ScenarioTrainingConfig(
                source.splitSeed(),
                source.modelSeed(),
                source.device(),
                source.ensembleMembers(),
                source.losoEvaluationMembers(),
                source.ablationMembers(),
                source.maxEpochs(),
                source.patience(),
                batchSize,
                source.learningRate(),
                source.weightDecay(),
                source.labelSmoothing(),
                source.minimumTrainPolicyGroups(),
                source.minimumValidationPolicyGroups(),
                source.minimumTestPolicyGroups(),
                source.minimumTrainRowsPerScenario(),
                source.minimumValidationRowsPerScenario(),
                source.minimumTestRowsPerScenario(),
                source.includeWeakCalibrationRows(),
                source.requireTargetVariation(),
                source.featureSelectionMode(),
                source.thresholds());
    }

    @Test
    void bootstrapResumesAcrossRequiredEnvironmentsAndPostMergesBeforeTraining() throws Exception {
        Path bootstrap = temp.resolve("bootstrap.csv");
        writeBootstrap(bootstrap, 6);
        var a = SourceScenario.of("env-a", 1, 4);
        var b = SourceScenario.of("env-b", 1, 4);
        TreeSet<SourceScenario> required = new TreeSet<>(List.of(a, b));
        ClosedLoopServices firstServices = createMockServices(false);
        ClosedLoopResult first = ClosedLoopRunner.run(config(bootstrap, required, "env-a", false), firstServices);
        assertThat(first.stage())
                .isEqualTo(io.euhedral_execution.training.checkpoint.enums.CheckpointStage.BOOTSTRAP_PENDING);
        assertThat(first.awaitingScenarios()).containsExactly(b);

        ClosedLoopServices secondServices = createMockServices(true);
        ClosedLoopResult second = ClosedLoopRunner.run(config(bootstrap, required, "env-b", true), secondServices);
        assertThat(second.stage())
                .isEqualTo(io.euhedral_execution.training.checkpoint.enums.CheckpointStage.READY_TO_TRAIN);
        assertThat(second.latestMerge()).isPresent();
        assertThat(second.awaitingScenarios()).isEmpty();
        verify(secondServices).merge(any());
        assertThat(second.packageDirectory()).isEmpty();

        int revision = Integer.parseInt(
                second.latestCheckpoint().getFileName().toString().substring("checkpoint-".length()));
        ClosedLoopConfig resumed = config(bootstrap, required, "env-b", true);
        var packaged = TrainingRunPackager.publish(new TrainingRunPackageRequest(
                resumed.workspace(),
                temp.resolve("packages"),
                new TrainingRunPackageInputs(
                        "training.partial.r%08d".formatted(revision),
                        "training",
                        revision,
                        resumed.schedulerSeed(),
                        resumed.commitSha(),
                        resumed.dirtyWorkingTree(),
                        resumed.benchmarkConfig(),
                        required)));
        assertThat(packaged.directory()).isDirectory();
        assertThat(packaged.directory().resolve("manifest.json")).isRegularFile();
        assertThat(packaged.directory().resolve("policy-scenario-measurements.csv"))
                .isRegularFile();
        assertThat(TrainingRunPackager.publish(new TrainingRunPackageRequest(
                                resumed.workspace(),
                                temp.resolve("packages"),
                                new TrainingRunPackageInputs(
                                        "training.partial.r%08d".formatted(revision),
                                        "training",
                                        revision,
                                        resumed.schedulerSeed(),
                                        resumed.commitSha(),
                                        resumed.dirtyWorkingTree(),
                                        resumed.benchmarkConfig(),
                                        required)))
                        .directory())
                .isEqualTo(packaged.directory());
        var reproduced = TrainingRunPackager.publish(new TrainingRunPackageRequest(
                resumed.workspace(),
                temp.resolve("reproduced"),
                new TrainingRunPackageInputs(
                        "training.partial.r%08d".formatted(revision),
                        "training",
                        revision,
                        resumed.schedulerSeed(),
                        resumed.commitSha(),
                        resumed.dirtyWorkingTree(),
                        resumed.benchmarkConfig(),
                        required)));
        assertThat(ArtifactFingerprint.sha256(reproduced.directory()))
                .isEqualTo(ArtifactFingerprint.sha256(packaged.directory()));
        assertThat(packaged.directory().resolve("vectors/robust-leaders.vectors.csv"))
                .isRegularFile();
        assertThat(packaged.directory().resolve("vectors/incomplete-promising.vectors.csv"))
                .isRegularFile();
        assertThat(packaged.directory().resolve("policy-scenario-measurements.csv"))
                .isRegularFile();
        assertThat(packaged.directory().resolve("reports/robust-ranking.md")).isRegularFile();
        Path unexpected = reproduced.directory().resolve("unexpected.txt");
        Files.writeString(unexpected, "tamper\n");
        assertThatThrownBy(() -> TrainingRunPackageValidator.validate(reproduced.directory()))
                .isInstanceOf(java.io.IOException.class);
        Files.delete(unexpected);
        assertThat(TrainingRunPackageValidator.validate(reproduced.directory()).directory())
                .isEqualTo(reproduced.directory());
    }

    @Test
    void generatedBootstrapVectorsArePersistedBeforeBenchmarking() throws Exception {
        SourceScenario scenario = SourceScenario.of("env-a", 1, 4);
        TreeSet<SourceScenario> required = new TreeSet<>(List.of(scenario));

        ClosedLoopRunner.run(config(null, required, "env-a", false), createBootstrapOnlyMockServices());

        Path persisted = temp.resolve("workspace/bootstrap/bootstrap-policies.vectors.csv");
        assertThat(persisted).isRegularFile();
        assertThat(io.euhedral_execution.training.scheduling.io.BootstrapPolicyCsv.read(persisted, 6))
                .containsExactlyInAnyOrderElementsOf(SequenceFinder.bootstrapVectors(1024, 6));
    }

    @Test
    void trainingFallsBackToColdStartAfterSparseStrictFailure() throws Exception {
        Path bootstrap = temp.resolve("bootstrap.csv");
        writeBootstrap(bootstrap, 6);
        SourceScenario scenario = SourceScenario.of("env-a", 1, 4);
        TreeSet<SourceScenario> required = new TreeSet<>(List.of(scenario));

        ClosedLoopRunner.run(config(bootstrap, required, "env-a", false), createBootstrapOnlyMockServices());

        ClosedLoopServices services = createFallbackTrainingMockServices();
        ClosedLoopResult result = ClosedLoopRunner.run(config(bootstrap, required, "env-a", true, 1), services);

        assertThat(result.stage()).isEqualTo(CheckpointStage.MODEL_REJECTED);
        ArgumentCaptor<ScenarioTrainingRequest> captor = ArgumentCaptor.forClass(ScenarioTrainingRequest.class);
        verify(services).train(captor.capture());
        ScenarioTrainingConfig capturedConfig = captor.getValue().config();
        assertThat(capturedConfig.requireTargetVariation()).isFalse();
        assertThat(capturedConfig.minimumValidationPolicyGroups()).isEqualTo(1);
    }

    @Test
    void bootstrapColdStartRejectionContinuesOnlyForFirstIteration() throws Exception {
        Path bootstrap = temp.resolve("bootstrap.csv");
        writeBootstrap(bootstrap, 6);
        SourceScenario scenario = SourceScenario.of("env-a", 1, 4);
        TreeSet<SourceScenario> required = new TreeSet<>(List.of(scenario));

        ClosedLoopRunner.run(config(bootstrap, required, "env-a", false, 2), createBootstrapOnlyMockServices());

        ClosedLoopServices firstPass = createRejectingTrainingMockServices(true, false, false);
        ClosedLoopResult readyForSecondIteration =
                ClosedLoopRunner.run(config(bootstrap, required, "env-a", true, 2), firstPass);

        assertThat(readyForSecondIteration.stage()).isEqualTo(CheckpointStage.READY_TO_TRAIN);
        ArgumentCaptor<ScenarioTrainingRequest> firstCaptor = ArgumentCaptor.forClass(ScenarioTrainingRequest.class);
        verify(firstPass).train(firstCaptor.capture());
        ScenarioTrainingConfig firstConfig = firstCaptor.getValue().config();
        assertThat(firstConfig.requireTargetVariation()).isFalse();
        assertThat(firstConfig.minimumValidationPolicyGroups()).isEqualTo(1);

        ClosedLoopServices secondPass = createRejectingTrainingMockServices(false, false, false);
        ClosedLoopResult terminal = ClosedLoopRunner.run(config(bootstrap, required, "env-a", true, 2), secondPass);

        assertThat(terminal.stage()).isEqualTo(CheckpointStage.MODEL_REJECTED);
        ArgumentCaptor<ScenarioTrainingRequest> secondCaptor = ArgumentCaptor.forClass(ScenarioTrainingRequest.class);
        verify(secondPass).train(secondCaptor.capture());
        ScenarioTrainingConfig secondConfig = secondCaptor.getValue().config();
        assertThat(secondConfig.requireTargetVariation()).isTrue();
        assertThat(secondConfig.minimumValidationPolicyGroups()).isEqualTo(10);
    }

    @Test
    void importedCalibrationColdStartRejectionContinuesWithNeutralScheduling() throws Exception {
        Path bootstrap = temp.resolve("bootstrap.csv");
        writeBootstrap(bootstrap, 6);
        SourceScenario scenario = SourceScenario.of("env-a", 1, 4);
        TreeSet<SourceScenario> required = new TreeSet<>(List.of(scenario));

        ClosedLoopRunner.run(config(bootstrap, required, "env-a", false, 2), createBootstrapOnlyMockServices());
        Path sourceWorkspace = temp.resolve("workspace");
        List<Path> evidence;
        try (var paths = Files.list(sourceWorkspace.resolve("evidence"))) {
            evidence = paths.filter(Files::isDirectory).sorted().toList();
        }
        Path importedWorkspace = temp.resolve("imported-workspace");
        ClosedLoopConfig imported = initialCalibrationConfig(
                importedWorkspace, sourceWorkspace.resolve("calibration-plan"), evidence, required, false, 2);

        ClosedLoopResult initialized = ClosedLoopRunner.run(imported, createMockServices(true));
        assertThat(initialized.stage()).isEqualTo(CheckpointStage.READY_TO_TRAIN);

        ClosedLoopServices services = createRejectingTrainingMockServices(true, false, true);
        ClosedLoopResult result = ClosedLoopRunner.run(
                initialCalibrationConfig(
                        importedWorkspace, sourceWorkspace.resolve("calibration-plan"), evidence, required, true, 2),
                services);

        assertThat(result.stage()).isEqualTo(CheckpointStage.READY_TO_TRAIN);
        ArgumentCaptor<ScenarioTrainingRequest> captor = ArgumentCaptor.forClass(ScenarioTrainingRequest.class);
        verify(services).train(captor.capture());
        ScenarioTrainingConfig capturedConfig = captor.getValue().config();
        assertThat(capturedConfig.requireTargetVariation()).isFalse();
        assertThat(capturedConfig.minimumValidationPolicyGroups()).isEqualTo(1);
    }

    @Test
    void bootstrapColdStartRejectionCompletesTheSingleConfiguredIteration() throws Exception {
        Path bootstrap = temp.resolve("bootstrap.csv");
        writeBootstrap(bootstrap, 6);
        SourceScenario scenario = SourceScenario.of("env-a", 1, 4);
        TreeSet<SourceScenario> required = new TreeSet<>(List.of(scenario));

        ClosedLoopRunner.run(config(bootstrap, required, "env-a", false, 1), createBootstrapOnlyMockServices());

        ClosedLoopServices services = createRejectingTrainingMockServices(false, false, false);
        ClosedLoopResult result = ClosedLoopRunner.run(config(bootstrap, required, "env-a", true, 1), services);

        assertThat(result.stage()).isEqualTo(CheckpointStage.RUN_COMPLETE);
        ArgumentCaptor<ScenarioTrainingRequest> captor = ArgumentCaptor.forClass(ScenarioTrainingRequest.class);
        verify(services).train(captor.capture());
        ScenarioTrainingConfig capturedConfig = captor.getValue().config();
        assertThat(capturedConfig.requireTargetVariation()).isFalse();
        assertThat(capturedConfig.minimumValidationPolicyGroups()).isEqualTo(1);
    }

    @Test
    void sparseSecondIterationRetriesColdStartAndContinuesWithNeutralScheduling() throws Exception {
        Path bootstrap = temp.resolve("bootstrap.csv");
        writeBootstrap(bootstrap, 6);
        SourceScenario scenario = SourceScenario.of("env-a", 1, 4);
        TreeSet<SourceScenario> required = new TreeSet<>(List.of(scenario));

        ClosedLoopRunner.run(config(bootstrap, required, "env-a", false, 2), createBootstrapOnlyMockServices());
        ClosedLoopResult readyForSecondIteration = ClosedLoopRunner.run(
                config(bootstrap, required, "env-a", true, 2), createRejectingTrainingMockServices(true, false, false));
        assertThat(readyForSecondIteration.stage()).isEqualTo(CheckpointStage.READY_TO_TRAIN);

        ClosedLoopServices sparseSecondIteration = createRejectingTrainingMockServices(false, true, false);
        ClosedLoopResult completed =
                ClosedLoopRunner.run(config(bootstrap, required, "env-a", true, 2), sparseSecondIteration);

        assertThat(completed.stage()).isEqualTo(CheckpointStage.RUN_COMPLETE);
        ArgumentCaptor<ScenarioTrainingRequest> captor = ArgumentCaptor.forClass(ScenarioTrainingRequest.class);
        verify(sparseSecondIteration, times(2)).train(captor.capture());
        List<ScenarioTrainingRequest> capturedRequests = captor.getAllValues();
        assertThat(capturedRequests).hasSize(2);
        assertThat(capturedRequests.get(0).config().requireTargetVariation()).isTrue();
        assertThat(capturedRequests.get(1).config().requireTargetVariation()).isFalse();
    }

    private ClosedLoopConfig config(
            Path bootstrap, TreeSet<SourceScenario> scenarios, String environment, boolean resume) {
        return config(bootstrap, scenarios, environment, resume, 1);
    }

    private ClosedLoopConfig config(
            Path bootstrap, TreeSet<SourceScenario> scenarios, String environment, boolean resume, int iterations) {
        return new ClosedLoopConfig(
                temp.resolve("workspace"),
                "training",
                iterations,
                6,
                scenarios,
                environment,
                1,
                77L,
                100,
                Optional.ofNullable(bootstrap),
                Optional.empty(),
                List.of(),
                Map.of(),
                "0".repeat(40),
                false,
                CandidateBudgetConfig.defaults(),
                new CandidateGenerationConfig(
                        32,
                        8,
                        new int[] {1, 1, 1, 1, 2, 2, 3, 5, 8, 16},
                        8,
                        7,
                        1,
                        new CmaEsConfig(false, 1, 1, 8, 0.2, 2)),
                new BenchmarkExecutionConfig(3, 100, 50, 8, 1_000, false),
                AnchorSelectionConfig.defaults(),
                CalibrationConfig.defaults(),
                AggregationConfig.defaults(),
                ScenarioTrainingConfig.defaults(),
                resume,
                temp.resolve("workspace/STOP"));
    }

    private ClosedLoopConfig initialCalibrationConfig(
            Path workspace,
            Path calibrationPlan,
            List<Path> evidence,
            TreeSet<SourceScenario> scenarios,
            boolean resume,
            int iterations) {
        ClosedLoopConfig base = config(null, scenarios, "env-a", resume, iterations);
        return new ClosedLoopConfig(
                workspace,
                base.trainingRunId(),
                base.iterations(),
                base.candidateBudget(),
                base.requiredScenarios(),
                base.activeEnvironmentId(),
                base.scenariosPerIteration(),
                base.schedulerSeed(),
                base.initialSobolCursor(),
                Optional.empty(),
                Optional.of(calibrationPlan),
                evidence,
                base.referenceOverrides(),
                base.commitSha(),
                base.dirtyWorkingTree(),
                base.budgetConfig(),
                base.generationConfig(),
                base.benchmarkConfig(),
                base.anchorSelectionConfig(),
                base.calibrationConfig(),
                base.aggregationConfig(),
                base.trainingConfig(),
                resume,
                workspace.resolve("STOP"));
    }

    private ClosedLoopServices createMockServices(boolean stopAfterMerge) throws Exception {
        ClosedLoopServices services = mock(ClosedLoopServices.class);
        AtomicBoolean mergeCalled = new AtomicBoolean(false);

        when(services.bootstrapCalibration(any()))
                .thenAnswer(inv -> DataMerger.bootstrapCalibrationV1(inv.getArgument(0)));
        when(services.merge(any())).thenAnswer(inv -> {
            mergeCalled.set(true);
            return DataMerger.mergeV1(inv.getArgument(0));
        });
        when(services.train(any())).thenThrow(new AssertionError("Training must not start after requested stop"));
        when(services.loadAcceptedModel(any(), any())).thenThrow(new AssertionError());
        when(services.benchmark(any(), any())).thenAnswer(inv -> createBenchmarkContext(inv.getArgument(0)));
        when(services.stopRequested()).thenAnswer(inv -> mergeCalled.get() && stopAfterMerge);
        when(services.activeCoreCount()).thenReturn(4);
        when(services.activeCpuSetHex()).thenReturn("f");

        return services;
    }

    private ClosedLoopServices createBootstrapOnlyMockServices() throws Exception {
        ClosedLoopServices services = mock(ClosedLoopServices.class);
        AtomicBoolean mergeCalled = new AtomicBoolean(false);

        when(services.bootstrapCalibration(any()))
                .thenAnswer(inv -> DataMerger.bootstrapCalibrationV1(inv.getArgument(0)));
        when(services.merge(any())).thenAnswer(inv -> {
            mergeCalled.set(true);
            return DataMerger.mergeV1(inv.getArgument(0));
        });
        when(services.train(any())).thenThrow(new AssertionError("Training should not run during bootstrap-only pass"));
        when(services.loadAcceptedModel(any(), any())).thenThrow(new AssertionError());
        when(services.benchmark(any(), any())).thenAnswer(inv -> createBenchmarkContext(inv.getArgument(0)));
        when(services.stopRequested()).thenAnswer(inv -> mergeCalled.get());
        when(services.activeCoreCount()).thenReturn(4);
        when(services.activeCpuSetHex()).thenReturn("f");

        return services;
    }

    private ClosedLoopServices createFallbackTrainingMockServices() throws Exception {
        ClosedLoopServices services = mock(ClosedLoopServices.class);
        AtomicBoolean trained = new AtomicBoolean(false);

        when(services.bootstrapCalibration(any())).thenThrow(new AssertionError());
        when(services.merge(any())).thenThrow(new AssertionError());
        when(services.train(any())).thenAnswer(inv -> {
            ScenarioTrainingRequest request = inv.getArgument(0);
            if (request.config().requireTargetVariation()) {
                throw new InsufficientScenarioLearningDataException("validation has too few policy groups");
            }
            trained.set(true);
            return io.euhedral_execution.training.learning.AuditScenarioModelFixture.writeRejected(
                    request.modelDirectory(),
                    request.requiredScenarios(),
                    request.config(),
                    SchedulingFixtures.policy(999),
                    request.commitSha(),
                    request.dirtyWorkingTree());
        });
        when(services.loadAcceptedModel(any(), any())).thenThrow(new AssertionError());
        when(services.benchmark(any(), any())).thenThrow(new AssertionError());
        when(services.stopRequested()).thenAnswer(inv -> trained.get());
        when(services.activeCoreCount()).thenReturn(4);
        when(services.activeCpuSetHex()).thenReturn("f");

        return services;
    }

    private ClosedLoopServices createRejectingTrainingMockServices(
            boolean stopAfterFirstMerge, boolean failStrictTraining, boolean storeStrictMetadata) throws Exception {
        ClosedLoopServices services = mock(ClosedLoopServices.class);
        AtomicBoolean mergeCalled = new AtomicBoolean(false);

        when(services.bootstrapCalibration(any())).thenThrow(new AssertionError());
        when(services.merge(any())).thenAnswer(inv -> {
            mergeCalled.set(true);
            return DataMerger.mergeV1(inv.getArgument(0));
        });
        when(services.train(any())).thenAnswer(inv -> {
            ScenarioTrainingRequest request = inv.getArgument(0);
            if (failStrictTraining && request.config().requireTargetVariation()) {
                throw new InsufficientScenarioLearningDataException("train lacks rows for a required scenario");
            }
            ScenarioTrainingConfig storedConfig =
                    withBatchSize(storeStrictMetadata ? ScenarioTrainingConfig.defaults() : request.config(), 4);
            return io.euhedral_execution.training.learning.AuditScenarioModelFixture.writeRejected(
                    request.modelDirectory(),
                    request.requiredScenarios(),
                    storedConfig,
                    SchedulingFixtures.policy(999),
                    request.commitSha(),
                    request.dirtyWorkingTree());
        });
        when(services.loadAcceptedModel(any(), any()))
                .thenThrow(new AssertionError("Rejected models should use the neutral predictor"));
        when(services.benchmark(any(), any())).thenAnswer(inv -> createBenchmarkContext(inv.getArgument(0)));
        when(services.stopRequested()).thenAnswer(inv -> mergeCalled.get() && stopAfterFirstMerge);
        when(services.activeCoreCount()).thenReturn(4);
        when(services.activeCpuSetHex()).thenReturn("f");

        return services;
    }

    private BenchmarkRunContext createBenchmarkContext(NativeBenchmarkRunPlan plan) {
        Instant start = Instant.EPOCH;
        BenchmarkRunDescriptor descriptor = new BenchmarkRunDescriptor(
                1,
                plan.benchmarkRunId(),
                plan.iteration(),
                plan.candidateCohortId(),
                plan.scenario(),
                plan.commitSha(),
                plan.dirtyWorkingTree(),
                EvidenceOrigin.NATIVE,
                start,
                plan.parameters());
        try (ObservationBundleWriter writer = ObservationBundleWriter.open(plan.outputBundle(), descriptor)) {
            plan.policies().forEach(writer::registerPolicy);
            long offset = 0;
            for (var policy : plan.policies()) {
                for (int repetition = 1; repetition <= plan.executionConfig().expectedRepetitions(); repetition++) {
                    Instant observationStart = start.plusNanos(offset);
                    long elapsed = 100;
                    long frames = 100;
                    writer.write(new BenchmarkObservation(
                            new ObservationKey(
                                    plan.benchmarkRunId(),
                                    plan.scenario(),
                                    policy.policy().id(),
                                    repetition),
                            descriptor,
                            policy,
                            ObservationStatus.SUCCESS,
                            MeasurementEncoding.COUNTER_DERIVED,
                            observationStart,
                            observationStart.plusNanos(elapsed),
                            OptionalLong.of(elapsed),
                            OptionalLong.of(frames),
                            OptionalDouble.of(frames * 1_000_000_000.0 / elapsed),
                            ""));
                    offset += elapsed;
                }
            }
            return writer.complete(start.plusNanos(offset));
        }
    }
}
