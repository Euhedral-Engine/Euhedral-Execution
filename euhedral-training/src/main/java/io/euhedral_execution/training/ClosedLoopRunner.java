package io.euhedral_execution.training;

import io.euhedral_execution.training.benchmark.data.NativeBenchmarkRunPlan;
import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import io.euhedral_execution.training.checkpoint.CheckpointSnapshotCodec;
import io.euhedral_execution.training.checkpoint.ClosedLoopConfigFingerprint;
import io.euhedral_execution.training.checkpoint.WorkspaceLock;
import io.euhedral_execution.training.checkpoint.data.ArtifactReference;
import io.euhedral_execution.training.checkpoint.data.ClosedLoopCheckpoint;
import io.euhedral_execution.training.checkpoint.data.EvidenceIndexEntry;
import io.euhedral_execution.training.checkpoint.data.LoadedCheckpoint;
import io.euhedral_execution.training.checkpoint.data.PendingBenchmarkRun;
import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.checkpoint.enums.EvidenceSource;
import io.euhedral_execution.training.checkpoint.enums.PendingRunStatus;
import io.euhedral_execution.training.config.ClosedLoopConfig;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.ClosedLoopResult;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.io.ObservationBundle;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.learning.InsufficientScenarioLearningDataException;
import io.euhedral_execution.training.learning.ScenarioConditionedModel;
import io.euhedral_execution.training.learning.ScenarioModelTrainer;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.data.PolicyPredictionCurve;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.learning.enums.ModelAcceptanceStatus;
import io.euhedral_execution.training.learning.inputs.ScenarioInputs;
import io.euhedral_execution.training.learning.inputs.ScenarioTrainingRequest;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadata;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadataCodec;
import io.euhedral_execution.training.learning.output.ScenarioTrainingArtifacts;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.merge.data.CalibrationPlanCsv;
import io.euhedral_execution.training.optimization.PolicyCurvePredictor;
import io.euhedral_execution.training.optimization.PredictedPolicyRanker;
import io.euhedral_execution.training.optimization.data.CandidateGenerationRequest;
import io.euhedral_execution.training.packaging.TrainingRunPackager;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageRequest;
import io.euhedral_execution.training.packaging.data.TrainingRunPackage;
import io.euhedral_execution.training.scheduling.BootstrapScheduler;
import io.euhedral_execution.training.scheduling.CandidateScheduler;
import io.euhedral_execution.training.scheduling.CarryForwardQueue;
import io.euhedral_execution.training.scheduling.ScenarioRotation;
import io.euhedral_execution.training.scheduling.data.IterationSchedule;
import io.euhedral_execution.training.scheduling.data.OptimizationCorpusView;
import io.euhedral_execution.training.scheduling.data.RotationGroup;
import io.euhedral_execution.training.scheduling.io.BootstrapPolicyCsv;
import io.euhedral_execution.training.scheduling.io.OptimizationCorpusReader;
import io.euhedral_execution.training.scheduling.io.ScheduleCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClosedLoopRunner {
    private static final int GENERATED_BOOTSTRAP_START_INDEX = 1024;
    private static final Logger LOGGER = LoggerFactory.getLogger(ClosedLoopRunner.class);

    public static final class StopRequested extends RuntimeException {
        private StopRequested() {
            super(null, null, false, false);
        }
    }

    static StopRequested stopSignal() {
        return new StopRequested();
    }

    public static ClosedLoopResult run(ClosedLoopConfig config) throws Exception {
        return runAndPackage(
                config,
                new ProductionServices(config.stopFile()),
                config.workspace().resolve("packages"));
    }

    static ClosedLoopResult runAndPackage(ClosedLoopConfig config, ClosedLoopServices services, Path outputRoot)
            throws Exception {
        ClosedLoopResult result = run(config, services);
        int revision = Integer.parseInt(
                result.latestCheckpoint().getFileName().toString().substring("checkpoint-".length()));
        String packageId = result.stage() == CheckpointStage.RUN_COMPLETE
                ? config.trainingRunId()
                : "%s.partial.r%08d".formatted(config.trainingRunId(), revision);
        TrainingRunPackage packaged = TrainingRunPackager.publish(new TrainingRunPackageRequest(
                config.workspace(),
                outputRoot,
                new TrainingRunPackageInputs(
                        packageId,
                        config.trainingRunId(),
                        revision,
                        config.schedulerSeed(),
                        config.commitSha(),
                        config.dirtyWorkingTree(),
                        config.benchmarkConfig(),
                        config.requiredScenarios())));
        return new ClosedLoopResult(
                result.stage(),
                result.nextIteration(),
                result.latestCheckpoint(),
                result.latestMerge(),
                result.latestModel(),
                result.awaitingScenarios(),
                Optional.of(packaged.directory()));
    }

    static boolean stopRequested(Path stopFile) {
        try {
            return Files.readAttributes(
                            stopFile, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isRegularFile();
        } catch (NoSuchFileException missing) {
            return false;
        } catch (IOException error) {
            throw new UncheckedIOException("Unable to inspect stop file", error);
        }
    }

    static ClosedLoopResult run(ClosedLoopConfig config, ClosedLoopServices services) throws Exception {
        try (WorkspaceLock ignored = WorkspaceLock.acquire(config.workspace());
                StagedInitialCalibrationPlan stagedPlan = stageInitialCalibrationPlan(config)) {
            ClosedLoopConfig effectiveConfig = stagedPlan.config();
            String configHash = ClosedLoopConfigFingerprint.sha256(effectiveConfig);
            Optional<LoadedCheckpoint> loaded = CheckpointSnapshotCodec.loadLatest(
                    effectiveConfig.workspace(), effectiveConfig.trainingRunId(), configHash);
            if (!effectiveConfig.resume() && loaded.isPresent()) {
                LOGGER.error(
                        "Resume is false but checkpoint exists: workspace={}, loadedCheckpoint={}",
                        effectiveConfig.workspace(),
                        loaded.get().snapshotDirectory());
                throw new IllegalArgumentException("A complete Phase 3 checkpoint already exists");
            }
            LoadedCheckpoint current = loaded.orElseGet(() -> {
                try {
                    return initialize(effectiveConfig, services, configHash);
                } catch (Exception error) {
                    throw new InitializationFailure(error);
                }
            });
            validateResumeArtifacts(effectiveConfig, current.checkpoint());
            rejectUnexpectedEvidence(effectiveConfig.workspace(), current.checkpoint());
            if (effectiveConfig.initialCalibrationPlan().isEmpty()) {
                resolveBootstrapPolicies(effectiveConfig);
            }
            if (current.checkpoint().stage() == CheckpointStage.BOOTSTRAP_PENDING) {
                try {
                    current = runBootstrap(effectiveConfig, services, current);
                } catch (StopRequested stop) {
                    return result(current, awaiting(effectiveConfig, current.checkpoint()));
                }
                if (current.checkpoint().stage() == CheckpointStage.BOOTSTRAP_PENDING) {
                    return result(current, awaiting(effectiveConfig, current.checkpoint()));
                }
            }
            while (current.checkpoint().stage() != CheckpointStage.RUN_COMPLETE) {
                if (services.stopRequested()) {
                    return result(current, new TreeSet<>());
                }
                if (current.checkpoint().stage() == CheckpointStage.MODEL_REJECTED
                        && !shouldContinueRejectedSeedModel(effectiveConfig, current.checkpoint())) {
                    return result(current, new TreeSet<>());
                }
                current = switch (current.checkpoint().stage()) {
                    case READY_TO_TRAIN -> train(effectiveConfig, services, current);
                    case MODEL_READY, MODEL_REJECTED -> schedule(effectiveConfig, services, current);
                    case SCHEDULE_READY ->
                        transition(effectiveConfig, current, copy(current.checkpoint(), CheckpointStage.BENCHMARKING));
                    case BENCHMARKING -> benchmark(effectiveConfig, services, current);
                    case READY_TO_MERGE -> merge(effectiveConfig, services, current);
                    default ->
                        throw new IllegalStateException("Unexpected closed-loop stage "
                                + current.checkpoint().stage());
                };
            }
            return result(current, new TreeSet<>());
        } catch (InitializationFailure failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw failure;
        }
    }

    private static LoadedCheckpoint initialize(ClosedLoopConfig config, ClosedLoopServices services, String configHash)
            throws Exception {
        Files.createDirectories(config.workspace());
        SortedMap<RotationGroup, Integer> cursors = initialCursors(config);
        if (config.initialCalibrationPlan().isPresent()) {
            Path planDirectory = config.workspace().resolve("calibration-plan");
            copyDirectoryAtomically(config.initialCalibrationPlan().get(), planDirectory);
            CalibrationPlan plan = CalibrationPlanCsv.read(planDirectory, config.requiredScenarios());
            List<EvidenceIndexEntry> initialEvidence = importInitialEvidence(config, plan);
            requireReferenceEvidence(plan, initialEvidence);
            if (plan.anchors().fixedAnchors().size() >= config.candidateBudget()) {
                LOGGER.error("Anchors: {} Budget: {}", plan.anchors().fixedAnchors(), config.candidateBudget());
                throw new IllegalArgumentException("Anchor count must be below policy budget");
            }
            Path mergeDirectory = config.workspace().resolve("merges/merge-000000");
            DataMerger.MergeArtifacts merge = services.merge(new DataMerger.MergeRequest(
                    evidencePaths(config.workspace(), initialEvidence),
                    config.requiredScenarios(),
                    plan,
                    mergeDirectory,
                    config.calibrationConfig(),
                    config.aggregationConfig()));
            ClosedLoopCheckpoint checkpoint = new ClosedLoopCheckpoint(
                    1,
                    config.trainingRunId(),
                    1,
                    CheckpointStage.READY_TO_TRAIN,
                    1,
                    config.initialSobolCursor(),
                    configHash,
                    config.requiredScenarios(),
                    cursors,
                    initialEvidence,
                    List.of(),
                    Optional.of(plan.anchors().anchorSetId()),
                    Optional.of(reference(config.workspace(), planDirectory)),
                    Optional.of(
                            reference(config.workspace(), merge.robustRanking().getParent())),
                    Optional.empty(),
                    Optional.empty(),
                    List.of());
            return CheckpointSnapshotCodec.writeNext(config.workspace(), checkpoint);
        }
        List<EvidenceIndexEntry> initialEvidence = importInitialEvidence(config, null);
        List<io.euhedral_execution.training.data.PolicyVector> policies = resolveBootstrapPolicies(config);
        int targetAnchors = config.anchorSelectionConfig().targetCount(config.candidateBudget());
        if (policies.size() <= targetAnchors) {
            LOGGER.error("Policies: {} Anchors: {}", policies.size(), targetAnchors);
            throw new IllegalArgumentException("Bootstrap budget must exceed anchor target");
        }
        ClosedLoopCheckpoint checkpoint = new ClosedLoopCheckpoint(
                1,
                config.trainingRunId(),
                1,
                CheckpointStage.BOOTSTRAP_PENDING,
                1,
                config.initialSobolCursor(),
                configHash,
                config.requiredScenarios(),
                cursors,
                initialEvidence,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
        return CheckpointSnapshotCodec.writeNext(config.workspace(), checkpoint);
    }

    private static StagedInitialCalibrationPlan stageInitialCalibrationPlan(ClosedLoopConfig config) throws Exception {
        if (config.initialCalibrationPlan().isEmpty()) {
            return new StagedInitialCalibrationPlan(config, Optional.empty());
        }
        Path source =
                config.initialCalibrationPlan().orElseThrow().toAbsolutePath().normalize();
        Path snapshot = temporarySibling(config.workspace().resolve("initial-calibration-plan"));
        Path sourceAnchors = source.resolve("fixed-anchors.csv");
        Path sourceReferences = source.resolve("reference-runs.csv");
        String anchorsHashBefore = ArtifactFingerprint.sha256(sourceAnchors);
        String referencesHashBefore = ArtifactFingerprint.sha256(sourceReferences);

        Files.createDirectories(snapshot);
        copyFileAtomically(sourceAnchors, snapshot.resolve("fixed-anchors.csv"));
        copyFileAtomically(sourceReferences, snapshot.resolve("reference-runs.csv"));

        String anchorsHashAfter = ArtifactFingerprint.sha256(sourceAnchors);
        String referencesHashAfter = ArtifactFingerprint.sha256(sourceReferences);

        if (!anchorsHashBefore.equals(anchorsHashAfter) || !referencesHashBefore.equals(referencesHashAfter)) {
            LOGGER.error(
                    "Initial calibration plan changed while being staged: source={}, anchorsBefore={}, anchorsAfter={}, refBefore={}, refAfter={}",
                    source,
                    anchorsHashBefore,
                    anchorsHashAfter,
                    referencesHashBefore,
                    referencesHashAfter);
            deleteRecursively(snapshot);
            throw new IllegalArgumentException("Initial calibration plan changed while being staged");
        }
        return new StagedInitialCalibrationPlan(withInitialCalibrationPlan(config, snapshot), Optional.of(snapshot));
    }

    private static ClosedLoopConfig withInitialCalibrationPlan(ClosedLoopConfig config, Path initialCalibrationPlan) {
        return new ClosedLoopConfig(
                config.workspace(),
                config.trainingRunId(),
                config.iterations(),
                config.candidateBudget(),
                config.requiredScenarios(),
                config.activeEnvironmentId(),
                config.scenariosPerIteration(),
                config.schedulerSeed(),
                config.initialSobolCursor(),
                config.bootstrapPolicies(),
                Optional.of(initialCalibrationPlan.toAbsolutePath().normalize()),
                config.initialObservationBundleDirectory(),
                config.initialObservationBundles(),
                config.referenceOverrides(),
                config.commitSha(),
                config.dirtyWorkingTree(),
                config.budgetConfig(),
                config.generationConfig(),
                config.benchmarkConfig(),
                config.anchorSelectionConfig(),
                config.calibrationConfig(),
                config.aggregationConfig(),
                config.trainingConfig(),
                config.resume(),
                config.stopFile());
    }

    private static LoadedCheckpoint runBootstrap(
            ClosedLoopConfig config, ClosedLoopServices services, LoadedCheckpoint loaded) throws Exception {
        LOGGER.info("Running bootstrap calibration.");

        LoadedCheckpoint current = loaded;
        ClosedLoopCheckpoint checkpoint = current.checkpoint();
        List<SourceScenario> runnable = runnable(config, services);
        List<io.euhedral_execution.training.data.PolicyVector> policies = resolveBootstrapPolicies(config);
        Set<SourceScenario> completeScenarios = checkpoint.evidence().stream()
                .filter(entry -> entry.source() == EvidenceSource.BOOTSTRAP)
                .map(EvidenceIndexEntry::scenario)
                .collect(java.util.stream.Collectors.toSet());
        for (SourceScenario scenario : runnable) {
            if (completeScenarios.contains(scenario)) {
                continue;
            }
            Path scheduleDirectory =
                    config.workspace().resolve("bootstrap/schedules").resolve(scenario.canonical());
            IterationSchedule schedule;
            if (Files.exists(scheduleDirectory.resolve("COMPLETE"))) {
                schedule = ScheduleCodec.read(
                        scheduleDirectory,
                        config.requiredScenarios(),
                        config.trainingRunId(),
                        config.schedulerSeed(),
                        config.commitSha(),
                        config.dirtyWorkingTree(),
                        config.benchmarkConfig());
            } else {
                schedule = BootstrapScheduler.create(
                        config.trainingRunId(),
                        scenario,
                        policies,
                        config.schedulerSeed(),
                        checkpoint.sobolCursor(),
                        config.commitSha(),
                        config.dirtyWorkingTree(),
                        services.activeCpuSetHex(),
                        config.benchmarkConfig());
                ScheduleCodec.write(scheduleDirectory, schedule);
            }
            var run = schedule.runs().getFirst();
            PendingBenchmarkRun pending = new PendingBenchmarkRun(
                    0,
                    io.euhedral_execution.training.scheduling.enums.RunKind.BOOTSTRAP,
                    scenario,
                    run.benchmarkRunId(),
                    run.candidateCohortId(),
                    reference(config.workspace(), scheduleDirectory),
                    "evidence/" + run.benchmarkRunId(),
                    PendingRunStatus.PENDING);
            ArrayList<PendingBenchmarkRun> pendingRows = new ArrayList<>(checkpoint.pendingRuns());
            pendingRows.removeIf(row -> row.scenario().equals(scenario));
            pendingRows.add(pending);
            checkpoint = new ClosedLoopCheckpoint(
                    1,
                    checkpoint.trainingRunId(),
                    checkpoint.revision() + 1,
                    CheckpointStage.BOOTSTRAP_PENDING,
                    checkpoint.nextIteration(),
                    checkpoint.sobolCursor(),
                    checkpoint.configSha256(),
                    checkpoint.requiredScenarios(),
                    checkpoint.rotationCursors(),
                    checkpoint.evidence(),
                    checkpoint.carryForward(),
                    checkpoint.anchorSetId(),
                    checkpoint.calibrationPlan(),
                    checkpoint.latestMerge(),
                    checkpoint.latestModel(),
                    checkpoint.pendingSchedule(),
                    pendingRows);
            CheckpointSnapshotCodec.writeNext(config.workspace(), checkpoint);
            Path output = config.workspace().resolve(pending.evidenceRelativePath());
            BenchmarkRunContext context;
            if (Files.isRegularFile(output.resolve("COMPLETE"))) {
                context = adopt(output, pending);
            } else {
                context = services.benchmark(plan(config, schedule, run, output), services::stopRequested);
            }
            EvidenceIndexEntry evidence = new EvidenceIndexEntry(
                    context.descriptor().benchmarkRunId(),
                    scenario,
                    reference(config.workspace(), output),
                    EvidenceSource.BOOTSTRAP);
            checkpoint = completePending(checkpoint, pending, evidence, CheckpointStage.BOOTSTRAP_PENDING);
            current = CheckpointSnapshotCodec.writeNext(config.workspace(), checkpoint);
        }
        Set<SourceScenario> completed = checkpoint.evidence().stream()
                .filter(entry -> entry.source() == EvidenceSource.BOOTSTRAP)
                .map(EvidenceIndexEntry::scenario)
                .collect(java.util.stream.Collectors.toSet());
        if (!completed.containsAll(config.requiredScenarios())) {
            return current;
        }
        Path planDirectory = config.workspace().resolve("calibration-plan");
        CalibrationPlan plan;
        if (Files.isDirectory(planDirectory)) {
            plan = CalibrationPlanCsv.read(planDirectory, config.requiredScenarios());
        } else {
            plan = services.bootstrapCalibration(new DataMerger.CalibrationBootstrapRequest(
                    evidencePaths(config.workspace(), checkpoint.evidence()),
                    config.requiredScenarios(),
                    config.candidateBudget(),
                    config.referenceOverrides(),
                    planDirectory,
                    config.anchorSelectionConfig(),
                    config.aggregationConfig()));
        }
        Path mergeDirectory = config.workspace().resolve("merges/merge-000000");
        if (!Files.isDirectory(mergeDirectory)) {
            services.merge(new DataMerger.MergeRequest(
                    evidencePaths(config.workspace(), checkpoint.evidence()),
                    config.requiredScenarios(),
                    plan,
                    mergeDirectory,
                    config.calibrationConfig(),
                    config.aggregationConfig()));
        }
        ClosedLoopCheckpoint ready = new ClosedLoopCheckpoint(
                1,
                checkpoint.trainingRunId(),
                checkpoint.revision() + 1,
                CheckpointStage.READY_TO_TRAIN,
                1,
                checkpoint.sobolCursor(),
                checkpoint.configSha256(),
                checkpoint.requiredScenarios(),
                checkpoint.rotationCursors(),
                checkpoint.evidence(),
                List.of(),
                Optional.of(plan.anchors().anchorSetId()),
                Optional.of(reference(config.workspace(), planDirectory)),
                Optional.of(reference(config.workspace(), mergeDirectory)),
                Optional.empty(),
                Optional.empty(),
                List.of());
        return CheckpointSnapshotCodec.writeNext(config.workspace(), ready);
    }

    private static List<io.euhedral_execution.training.data.PolicyVector> resolveBootstrapPolicies(
            ClosedLoopConfig config) throws IOException {
        Path persisted = config.workspace().resolve("bootstrap/bootstrap-policies.vectors.csv");
        if (Files.isRegularFile(persisted)) {
            return BootstrapPolicyCsv.read(persisted, config.candidateBudget());
        }
        if (config.bootstrapPolicies().isPresent()) {
            Path source = config.bootstrapPolicies().orElseThrow();
            copyFileAtomically(source, persisted);
            return BootstrapPolicyCsv.read(persisted, config.candidateBudget());
        }
        List<io.euhedral_execution.training.data.PolicyVector> generated =
                SequenceFinder.bootstrapVectors(GENERATED_BOOTSTRAP_START_INDEX, config.candidateBudget());
        BootstrapPolicyCsv.write(persisted, generated);
        return BootstrapPolicyCsv.read(persisted, config.candidateBudget());
    }

    private static LoadedCheckpoint train(ClosedLoopConfig config, ClosedLoopServices services, LoadedCheckpoint loaded)
            throws Exception {
        ClosedLoopCheckpoint checkpoint = loaded.checkpoint();
        int iteration = checkpoint.nextIteration();
        Path mergeDirectory =
                resolve(config.workspace(), checkpoint.latestMerge().orElseThrow());
        DataMerger.MergeArtifacts merge = artifacts(mergeDirectory);
        Path modelDirectory = config.workspace().resolve("models/model-%06d".formatted(iteration));
        ScenarioTrainingArtifacts trained;
        if (Files.isDirectory(modelDirectory)) {
            ScenarioModelMetadata metadata =
                    ScenarioModelMetadataCodec.read(modelDirectory.resolve(ScenarioModelMetadataCodec.FILE_NAME));
            trained = new ScenarioTrainingArtifacts(
                    modelDirectory,
                    modelDirectory.resolve(ScenarioModelMetadataCodec.FILE_NAME),
                    modelDirectory.resolve("grouped-evaluation.csv"),
                    modelDirectory.resolve("loso-evaluation.csv"),
                    modelDirectory.resolve("ablation-evaluation.csv"),
                    modelDirectory.resolve("training-history.csv"),
                    metadata.acceptanceStatus(),
                    metadata.featureSet());
        } else {
            boolean seedEvidence = hasSeedEvidence(checkpoint);
            ScenarioTrainingConfig trainingConfig =
                    iteration == 1 && seedEvidence ? config.trainingConfig().coldStart() : config.trainingConfig();
            trained = trainWithColdStartFallback(
                    config, services, merge, modelDirectory, trainingConfig, seedEvidence, iteration);
        }
        CheckpointStage stage = trained.acceptanceStatus() == ModelAcceptanceStatus.ACCEPTED
                ? CheckpointStage.MODEL_READY
                : CheckpointStage.MODEL_REJECTED;
        ClosedLoopCheckpoint next = new ClosedLoopCheckpoint(
                1,
                checkpoint.trainingRunId(),
                checkpoint.revision() + 1,
                stage,
                iteration,
                checkpoint.sobolCursor(),
                checkpoint.configSha256(),
                checkpoint.requiredScenarios(),
                checkpoint.rotationCursors(),
                checkpoint.evidence(),
                checkpoint.carryForward(),
                checkpoint.anchorSetId(),
                checkpoint.calibrationPlan(),
                checkpoint.latestMerge(),
                Optional.of(reference(config.workspace(), modelDirectory)),
                Optional.empty(),
                List.of());
        return CheckpointSnapshotCodec.writeNext(config.workspace(), next);
    }

    private static ScenarioTrainingArtifacts trainWithColdStartFallback(
            ClosedLoopConfig config,
            ClosedLoopServices services,
            DataMerger.MergeArtifacts merge,
            Path modelDirectory,
            ScenarioTrainingConfig trainingConfig,
            boolean seedEvidence,
            int iteration)
            throws Exception {
        ScenarioInputs inputs = ScenarioInputs.from(merge);
        try {
            return services.train(new ScenarioTrainingRequest(
                    inputs,
                    config.requiredScenarios(),
                    modelDirectory,
                    config.commitSha(),
                    config.dirtyWorkingTree(),
                    trainingConfig));
        } catch (InsufficientScenarioLearningDataException insufficient) {
            if (!seedEvidence) {
                throw insufficient;
            }
            ScenarioTrainingConfig coldStart = config.trainingConfig().coldStart();
            if (trainingConfig.equals(coldStart)) {
                throw insufficient;
            }
            LOGGER.warn(
                    "Iteration {} training data is still sparse; retrying with cold-start " + "config: {}",
                    iteration,
                    insufficient.getMessage());
            return services.train(new ScenarioTrainingRequest(
                    inputs,
                    config.requiredScenarios(),
                    modelDirectory,
                    config.commitSha(),
                    config.dirtyWorkingTree(),
                    coldStart));
        }
    }

    private static LoadedCheckpoint schedule(
            ClosedLoopConfig config, ClosedLoopServices services, LoadedCheckpoint loaded) throws Exception {
        ClosedLoopCheckpoint checkpoint = loaded.checkpoint();
        int iteration = checkpoint.nextIteration();
        Path modelDirectory =
                resolve(config.workspace(), checkpoint.latestModel().orElseThrow());
        ScenarioModelMetadata metadata =
                ScenarioModelMetadataCodec.read(modelDirectory.resolve(ScenarioModelMetadataCodec.FILE_NAME));
        boolean sparseDataFallback = checkpoint.stage() == CheckpointStage.MODEL_REJECTED
                && shouldContinueRejectedSeedModel(config, checkpoint);
        boolean isProduction = checkpoint.stage() == CheckpointStage.MODEL_READY;
        if (checkpoint.stage() == CheckpointStage.MODEL_REJECTED && !sparseDataFallback) {
            LOGGER.error(
                    "Disallowed model continuation: stage={}, sparseDataFallback={}",
                    checkpoint.stage(),
                    sparseDataFallback);
            throw new IllegalArgumentException("Only rejected sparse-data fallback models may continue");
        }
        if (isProduction && !metadata.deploymentEligible()
                || !metadata.requiredScenarios().equals(config.requiredScenarios())) {
            LOGGER.error(
                    "Accepted model scenario catalog mismatch: isProduction={}, deploymentEligible={}, modelScenarios={}, configScenarios={}",
                    isProduction,
                    metadata.deploymentEligible(),
                    metadata.requiredScenarios(),
                    config.requiredScenarios());
            throw new IllegalArgumentException("Accepted model scenario catalog mismatch");
        }
        Path scheduleDirectory = config.workspace().resolve("iterations/iteration-%06d/schedule".formatted(iteration));
        DataMerger.MergeArtifacts merge =
                artifacts(resolve(config.workspace(), checkpoint.latestMerge().orElseThrow()));
        OptimizationCorpusView corpus = OptimizationCorpusReader.read(merge, config.requiredScenarios());
        IterationSchedule expected;
        if (!isProduction) {
            PolicyCurvePredictor fallbackPredictor = neutralPredictor(config.requiredScenarios());
            var rescored = CarryForwardQueue.rescore(checkpoint.carryForward(), fallbackPredictor, iteration);
            List<SourceScenario> selected = ScenarioRotation.select(
                    config.requiredScenarios(),
                    checkpoint.rotationCursors(),
                    config.activeEnvironmentId(),
                    services.activeCoreCount(),
                    config.scenariosPerIteration());
            CalibrationPlan calibration = CalibrationPlanCsv.read(
                    resolve(config.workspace(), checkpoint.calibrationPlan().orElseThrow()),
                    config.requiredScenarios());
            var preparation = CandidateScheduler.prepare(
                    iteration,
                    config.candidateBudget(),
                    calibration,
                    corpus,
                    rescored,
                    selected,
                    config.budgetConfig(),
                    fallbackPredictor);
            var generated = SequenceFinder.generate(new CandidateGenerationRequest(
                    iteration,
                    preparation.baseExplorationCount(),
                    preparation.preAuditOverflowCount(),
                    preparation.disagreementAuditCount(),
                    checkpoint.sobolCursor(),
                    config.schedulerSeed(),
                    corpus,
                    calibration.anchors().fixedAnchors().stream()
                            .map(policy -> policy.id())
                            .collect(java.util.stream.Collectors.toSet()),
                    fallbackPredictor,
                    config.generationConfig()));
            expected = CandidateScheduler.complete(
                    config.trainingRunId(),
                    config.schedulerSeed(),
                    config.commitSha(),
                    config.dirtyWorkingTree(),
                    services.activeCpuSetHex(),
                    config.benchmarkConfig(),
                    preparation,
                    generated);
        } else {
            try (ScenarioConditionedModel model = services.loadAcceptedModel(
                    modelDirectory, metadata.producer().trainingDevice())) {
                PolicyCurvePredictor predictor = policies -> policies.isEmpty()
                        ? List.of()
                        : model.predictConfiguredCurves(policies).stream()
                                .map(curve -> PredictedPolicyRanker.summarize(curve, config.requiredScenarios()))
                                .toList();
                var rescored = CarryForwardQueue.rescore(checkpoint.carryForward(), predictor, iteration);
                List<SourceScenario> selected = ScenarioRotation.select(
                        config.requiredScenarios(),
                        checkpoint.rotationCursors(),
                        config.activeEnvironmentId(),
                        services.activeCoreCount(),
                        config.scenariosPerIteration());
                CalibrationPlan calibration = CalibrationPlanCsv.read(
                        resolve(config.workspace(), checkpoint.calibrationPlan().orElseThrow()),
                        config.requiredScenarios());
                var preparation = CandidateScheduler.prepare(
                        iteration,
                        config.candidateBudget(),
                        calibration,
                        corpus,
                        rescored,
                        selected,
                        config.budgetConfig(),
                        predictor);
                var generated = SequenceFinder.generate(new CandidateGenerationRequest(
                        iteration,
                        preparation.baseExplorationCount(),
                        preparation.preAuditOverflowCount(),
                        preparation.disagreementAuditCount(),
                        checkpoint.sobolCursor(),
                        config.schedulerSeed(),
                        corpus,
                        calibration.anchors().fixedAnchors().stream()
                                .map(policy -> policy.id())
                                .collect(java.util.stream.Collectors.toSet()),
                        predictor,
                        config.generationConfig()));
                expected = CandidateScheduler.complete(
                        config.trainingRunId(),
                        config.schedulerSeed(),
                        config.commitSha(),
                        config.dirtyWorkingTree(),
                        services.activeCpuSetHex(),
                        config.benchmarkConfig(),
                        preparation,
                        generated);
            }
        }
        IterationSchedule iterationSchedule;
        if (Files.isRegularFile(scheduleDirectory.resolve("COMPLETE"))) {
            IterationSchedule persisted = ScheduleCodec.read(
                    scheduleDirectory,
                    config.requiredScenarios(),
                    config.trainingRunId(),
                    config.schedulerSeed(),
                    config.commitSha(),
                    config.dirtyWorkingTree(),
                    config.benchmarkConfig());
            if (!samePersistedSchedule(persisted, expected)) {
                LOGGER.error(
                        "Published schedule does not match deterministic inputs: scheduleDir={}", scheduleDirectory);
                throw new IllegalArgumentException("Published schedule does not match deterministic inputs");
            }
            iterationSchedule = expected;
        } else {
            ScheduleCodec.write(scheduleDirectory, expected);
            iterationSchedule = expected;
        }
        ArtifactReference scheduleReference = reference(config.workspace(), scheduleDirectory);
        List<PendingBenchmarkRun> pending = iterationSchedule.runs().stream()
                .map(run -> new PendingBenchmarkRun(
                        iteration,
                        io.euhedral_execution.training.scheduling.enums.RunKind.NORMAL,
                        run.scenario(),
                        run.benchmarkRunId(),
                        run.candidateCohortId(),
                        scheduleReference,
                        "evidence/" + run.benchmarkRunId(),
                        PendingRunStatus.PENDING))
                .toList();
        ClosedLoopCheckpoint next = new ClosedLoopCheckpoint(
                1,
                checkpoint.trainingRunId(),
                checkpoint.revision() + 1,
                CheckpointStage.SCHEDULE_READY,
                iteration,
                iterationSchedule.nextSobolCursor(),
                checkpoint.configSha256(),
                checkpoint.requiredScenarios(),
                checkpoint.rotationCursors(),
                checkpoint.evidence(),
                checkpoint.carryForward(),
                checkpoint.anchorSetId(),
                checkpoint.calibrationPlan(),
                checkpoint.latestMerge(),
                checkpoint.latestModel(),
                Optional.of(scheduleReference),
                pending);
        return CheckpointSnapshotCodec.writeNext(config.workspace(), next);
    }

    private static boolean samePersistedSchedule(IterationSchedule persisted, IterationSchedule expected) {
        return persisted.trainingRunId().equals(expected.trainingRunId())
                && persisted.iteration() == expected.iteration()
                && persisted.runs().equals(expected.runs())
                && persisted.selectedPredictions().equals(expected.selectedPredictions())
                && persisted.carryAdmissions().equals(expected.carryAdmissions())
                && persisted.budgetReports().equals(expected.budgetReports());
    }

    private static PolicyCurvePredictor neutralPredictor(java.util.SortedSet<SourceScenario> requiredScenarios) {
        List<ScenarioPrediction> template = requiredScenarios.stream()
                .map(scenario -> new ScenarioPrediction(scenario, 0.5, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0))
                .toList();
        return policies -> policies.stream()
                .map(policy ->
                        PredictedPolicyRanker.summarize(new PolicyPredictionCurve(policy, template), requiredScenarios))
                .toList();
    }

    private static LoadedCheckpoint benchmark(
            ClosedLoopConfig config, ClosedLoopServices services, LoadedCheckpoint loaded) throws Exception {
        LoadedCheckpoint current = loaded;
        ClosedLoopCheckpoint checkpoint = current.checkpoint();
        Path scheduleDirectory =
                resolve(config.workspace(), checkpoint.pendingSchedule().orElseThrow());
        IterationSchedule schedule = ScheduleCodec.read(
                scheduleDirectory,
                config.requiredScenarios(),
                config.trainingRunId(),
                config.schedulerSeed(),
                config.commitSha(),
                config.dirtyWorkingTree(),
                config.benchmarkConfig());
        for (PendingBenchmarkRun pending : checkpoint.pendingRuns()) {
            if (pending.status() == PendingRunStatus.COMPLETE) {
                continue;
            }
            var run = schedule.runs().stream()
                    .filter(item -> item.benchmarkRunId().equals(pending.benchmarkRunId()))
                    .findFirst()
                    .orElseThrow();
            Path output = config.workspace().resolve(pending.evidenceRelativePath());
            BenchmarkRunContext context;
            try {
                context = Files.isRegularFile(output.resolve("COMPLETE"))
                        ? adopt(output, pending)
                        : services.benchmark(plan(config, schedule, run, output), services::stopRequested);
            } catch (StopRequested stop) {
                return current;
            }
            EvidenceIndexEntry evidence = new EvidenceIndexEntry(
                    context.descriptor().benchmarkRunId(),
                    pending.scenario(),
                    reference(config.workspace(), output),
                    EvidenceSource.ITERATION);
            checkpoint = completePending(checkpoint, pending, evidence, CheckpointStage.BENCHMARKING);
            current = CheckpointSnapshotCodec.writeNext(config.workspace(), checkpoint);
        }
        ClosedLoopCheckpoint ready = copy(checkpoint, CheckpointStage.READY_TO_MERGE);
        return CheckpointSnapshotCodec.writeNext(config.workspace(), ready);
    }

    private static LoadedCheckpoint merge(ClosedLoopConfig config, ClosedLoopServices services, LoadedCheckpoint loaded)
            throws Exception {
        ClosedLoopCheckpoint checkpoint = loaded.checkpoint();
        int iteration = checkpoint.nextIteration();
        CalibrationPlan calibration = CalibrationPlanCsv.read(
                resolve(config.workspace(), checkpoint.calibrationPlan().orElseThrow()), config.requiredScenarios());
        Path mergeDirectory = config.workspace().resolve("merges/merge-%06d".formatted(iteration));
        DataMerger.MergeArtifacts merge = Files.isDirectory(mergeDirectory)
                ? artifacts(mergeDirectory)
                : services.merge(new DataMerger.MergeRequest(
                        evidencePaths(config.workspace(), checkpoint.evidence()),
                        config.requiredScenarios(),
                        calibration,
                        mergeDirectory,
                        config.calibrationConfig(),
                        config.aggregationConfig()));
        OptimizationCorpusView corpus = OptimizationCorpusReader.read(merge, config.requiredScenarios());
        IterationSchedule schedule = ScheduleCodec.read(
                resolve(config.workspace(), checkpoint.pendingSchedule().orElseThrow()),
                config.requiredScenarios(),
                config.trainingRunId(),
                config.schedulerSeed(),
                config.commitSha(),
                config.dirtyWorkingTree(),
                config.benchmarkConfig());
        var carry = CarryForwardQueue.reconcile(checkpoint.carryForward(), corpus, schedule, iteration);
        SortedMap<RotationGroup, Integer> cursors = ScenarioRotation.advance(
                config.requiredScenarios(),
                checkpoint.rotationCursors(),
                schedule.runs().stream().map(run -> run.scenario()).toList());
        CheckpointStage stage =
                iteration == config.iterations() ? CheckpointStage.RUN_COMPLETE : CheckpointStage.READY_TO_TRAIN;
        ClosedLoopCheckpoint next = new ClosedLoopCheckpoint(
                1,
                checkpoint.trainingRunId(),
                checkpoint.revision() + 1,
                stage,
                iteration + 1,
                checkpoint.sobolCursor(),
                checkpoint.configSha256(),
                checkpoint.requiredScenarios(),
                cursors,
                checkpoint.evidence(),
                carry,
                checkpoint.anchorSetId(),
                checkpoint.calibrationPlan(),
                Optional.of(reference(config.workspace(), mergeDirectory)),
                checkpoint.latestModel(),
                Optional.empty(),
                List.of());
        return CheckpointSnapshotCodec.writeNext(config.workspace(), next);
    }

    private static ClosedLoopCheckpoint completePending(
            ClosedLoopCheckpoint checkpoint,
            PendingBenchmarkRun completed,
            EvidenceIndexEntry evidence,
            CheckpointStage stage) {
        ArrayList<EvidenceIndexEntry> evidenceRows = new ArrayList<>(checkpoint.evidence());
        if (evidenceRows.stream().noneMatch(row -> row.benchmarkRunId().equals(evidence.benchmarkRunId()))) {
            evidenceRows.add(evidence);
        }
        List<PendingBenchmarkRun> pending = checkpoint.pendingRuns().stream()
                .map(row -> row.benchmarkRunId().equals(completed.benchmarkRunId())
                        ? new PendingBenchmarkRun(
                                row.iteration(),
                                row.runKind(),
                                row.scenario(),
                                row.benchmarkRunId(),
                                row.candidateCohortId(),
                                row.schedule(),
                                row.evidenceRelativePath(),
                                PendingRunStatus.COMPLETE)
                        : row)
                .toList();
        return new ClosedLoopCheckpoint(
                1,
                checkpoint.trainingRunId(),
                checkpoint.revision() + 1,
                stage,
                checkpoint.nextIteration(),
                checkpoint.sobolCursor(),
                checkpoint.configSha256(),
                checkpoint.requiredScenarios(),
                checkpoint.rotationCursors(),
                evidenceRows,
                checkpoint.carryForward(),
                checkpoint.anchorSetId(),
                checkpoint.calibrationPlan(),
                checkpoint.latestMerge(),
                checkpoint.latestModel(),
                checkpoint.pendingSchedule(),
                pending);
    }

    private static LoadedCheckpoint transition(
            ClosedLoopConfig config, LoadedCheckpoint previous, ClosedLoopCheckpoint next) throws IOException {
        return CheckpointSnapshotCodec.writeNext(config.workspace(), next);
    }

    private static ClosedLoopCheckpoint copy(ClosedLoopCheckpoint checkpoint, CheckpointStage stage) {
        return new ClosedLoopCheckpoint(
                1,
                checkpoint.trainingRunId(),
                checkpoint.revision() + 1,
                stage,
                checkpoint.nextIteration(),
                checkpoint.sobolCursor(),
                checkpoint.configSha256(),
                checkpoint.requiredScenarios(),
                checkpoint.rotationCursors(),
                checkpoint.evidence(),
                checkpoint.carryForward(),
                checkpoint.anchorSetId(),
                checkpoint.calibrationPlan(),
                checkpoint.latestMerge(),
                checkpoint.latestModel(),
                checkpoint.pendingSchedule(),
                checkpoint.pendingRuns());
    }

    private static Path temporarySibling(Path target) {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Output requires a parent");
        }
        return parent.resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
    }

    private static NativeBenchmarkRunPlan plan(
            ClosedLoopConfig config,
            IterationSchedule schedule,
            io.euhedral_execution.training.scheduling.data.ScheduledRun run,
            Path output) {
        return new NativeBenchmarkRunPlan(
                config.trainingRunId(),
                schedule.iteration(),
                run.benchmarkRunId(),
                run.candidateCohortId(),
                run.scenario(),
                run.policies(),
                config.benchmarkConfig(),
                run.parameters(),
                config.schedulerSeed(),
                config.commitSha(),
                config.dirtyWorkingTree(),
                output);
    }

    private static BenchmarkRunContext adopt(Path output, PendingBenchmarkRun pending) {
        ObservationBundle bundle = ObservationBundleReader.read(output);
        if (!bundle.run().descriptor().benchmarkRunId().equals(pending.benchmarkRunId())
                || !bundle.run().descriptor().candidateCohortId().equals(pending.candidateCohortId())
                || !bundle.run().descriptor().scenario().equals(pending.scenario())) {
            LOGGER.error(
                    "Expected evidence bundle identity mismatch: bundleDescriptor={}, pendingRunId={}, pendingCohortId={}, pendingScenario={}",
                    bundle.run().descriptor(),
                    pending.benchmarkRunId(),
                    pending.candidateCohortId(),
                    pending.scenario());
            throw new IllegalArgumentException("Expected evidence bundle identity mismatch");
        }
        return bundle.run();
    }

    private static void rejectUnexpectedEvidence(Path workspace, ClosedLoopCheckpoint checkpoint) throws IOException {
        Path evidenceDirectory = workspace.resolve("evidence");
        if (!Files.isDirectory(evidenceDirectory)) {
            return;
        }
        Set<String> expected = new HashSet<>();
        checkpoint.evidence().forEach(row -> expected.add(row.benchmarkRunId()));
        checkpoint.pendingRuns().forEach(row -> expected.add(row.benchmarkRunId()));
        try (var stream = Files.list(evidenceDirectory)) {
            for (Path path : stream.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> Files.isRegularFile(path.resolve("COMPLETE")))
                    .toList()) {
                if (!expected.contains(path.getFileName().toString())) {
                    LOGGER.error("Unexpected complete evidence bundle: path={}, expected={}", path, expected);
                    throw new IllegalArgumentException("Unexpected complete evidence bundle " + path.getFileName());
                }
            }
        }
    }

    private static void validateResumeArtifacts(ClosedLoopConfig config, ClosedLoopCheckpoint checkpoint)
            throws IOException {
        if (checkpoint.pendingSchedule().isPresent()) {
            ScheduleCodec.read(
                    resolve(config.workspace(), checkpoint.pendingSchedule().orElseThrow()),
                    config.requiredScenarios(),
                    config.trainingRunId(),
                    config.schedulerSeed(),
                    config.commitSha(),
                    config.dirtyWorkingTree(),
                    config.benchmarkConfig());
        }
        if (checkpoint.latestModel().isPresent()) {
            ScenarioModelMetadata metadata = ScenarioModelMetadataCodec.read(
                    resolve(config.workspace(), checkpoint.latestModel().orElseThrow())
                            .resolve(ScenarioModelMetadataCodec.FILE_NAME));
            boolean deploymentEligibleRequired = checkpoint.stage() != CheckpointStage.MODEL_REJECTED
                    && !carriesRejectedSeedModel(config, checkpoint, metadata);
            if (metadata.deploymentEligible() != deploymentEligibleRequired
                    || !metadata.requiredScenarios().equals(config.requiredScenarios())) {
                LOGGER.error(
                        "Checkpoint model status mismatch: metadataEligible={}, requiredEligible={}, metadataScenarios={}, configScenarios={}",
                        metadata.deploymentEligible(),
                        deploymentEligibleRequired,
                        metadata.requiredScenarios(),
                        config.requiredScenarios());
                throw new IllegalArgumentException("Checkpoint model status mismatch");
            }
        }
    }

    private static boolean shouldContinueRejectedSeedModel(ClosedLoopConfig config, ClosedLoopCheckpoint checkpoint)
            throws IOException {
        if (checkpoint.stage() != CheckpointStage.MODEL_REJECTED
                || checkpoint.latestModel().isEmpty()) {
            return false;
        }
        ScenarioModelMetadata metadata = ScenarioModelMetadataCodec.read(
                resolve(config.workspace(), checkpoint.latestModel().orElseThrow())
                        .resolve(ScenarioModelMetadataCodec.FILE_NAME));
        return isContinuableRejectedSeedModel(config, checkpoint, metadata);
    }

    private static boolean carriesRejectedSeedModel(
            ClosedLoopConfig config, ClosedLoopCheckpoint checkpoint, ScenarioModelMetadata metadata) {
        if (!isContinuableRejectedSeedModel(config, checkpoint, metadata)) {
            return false;
        }
        return switch (checkpoint.stage()) {
            case SCHEDULE_READY, BENCHMARKING, READY_TO_MERGE, READY_TO_TRAIN, RUN_COMPLETE -> true;
            default -> false;
        };
    }

    private static boolean isContinuableRejectedSeedModel(
            ClosedLoopConfig config, ClosedLoopCheckpoint checkpoint, ScenarioModelMetadata metadata) {
        return hasSeedEvidence(checkpoint)
                && !metadata.deploymentEligible()
                && (isSparseDataModelConfig(config, metadata.trainingConfig())
                        || isLegacyImportedFirstModel(checkpoint));
    }

    private static boolean hasSeedEvidence(ClosedLoopCheckpoint checkpoint) {
        return checkpoint.evidence().stream()
                .anyMatch(entry ->
                        entry.source() == EvidenceSource.BOOTSTRAP || entry.source() == EvidenceSource.INITIAL);
    }

    private static boolean isLegacyImportedFirstModel(ClosedLoopCheckpoint checkpoint) {
        return checkpoint.evidence().stream().anyMatch(entry -> entry.source() == EvidenceSource.INITIAL)
                && checkpoint
                        .latestModel()
                        .map(model -> model.relativePath().equals("models/model-000001"))
                        .orElse(false);
    }

    private static boolean isSparseDataModelConfig(ClosedLoopConfig config, ScenarioTrainingConfig trainingConfig) {
        return trainingConfig.isEffectiveVersionOf(config.trainingConfig().coldStart());
    }

    private static List<EvidenceIndexEntry> importInitialEvidence(ClosedLoopConfig config, CalibrationPlan plan)
            throws Exception {
        ArrayList<EvidenceIndexEntry> result = new ArrayList<>();
        Set<String> runIds = new HashSet<>();
        List<Path> sources = plan == null
                ? config.initialObservationBundles()
                : InitialObservationBundleResolver.resolve(config, plan);
        for (Path source : sources) {
            ObservationBundle bundle = ObservationBundleReader.read(source);
            String runId = bundle.run().descriptor().benchmarkRunId();
            if (!runIds.add(runId)) {
                throw new IllegalArgumentException("Duplicate initial benchmark run");
            }
            Path target = config.workspace().resolve("evidence").resolve(runId);
            copyDirectoryAtomically(source, target);
            result.add(new EvidenceIndexEntry(
                    runId,
                    bundle.run().descriptor().scenario(),
                    reference(config.workspace(), target),
                    EvidenceSource.INITIAL));
        }
        return result.stream()
                .sorted(Comparator.comparing(EvidenceIndexEntry::benchmarkRunId))
                .toList();
    }

    private static void requireReferenceEvidence(CalibrationPlan plan, List<EvidenceIndexEntry> evidence) {
        Set<String> runIds =
                evidence.stream().map(EvidenceIndexEntry::benchmarkRunId).collect(java.util.stream.Collectors.toSet());
        if (!runIds.containsAll(plan.references().referenceRunIds().values())) {
            throw new IllegalArgumentException("Calibration references lack initial evidence");
        }
    }

    private static SortedMap<RotationGroup, Integer> initialCursors(ClosedLoopConfig config) {
        TreeMap<RotationGroup, Integer> result = new TreeMap<>();
        config.requiredScenarios()
                .forEach(scenario -> result.put(
                        new RotationGroup(scenario.environmentId(), scenario.availablePhysicalCoreCount()), 0));
        return result;
    }

    private static List<SourceScenario> runnable(ClosedLoopConfig config, ClosedLoopServices services) {
        List<SourceScenario> result = config.requiredScenarios().stream()
                .filter(scenario -> scenario.environmentId().equals(config.activeEnvironmentId()))
                .filter(scenario -> scenario.availablePhysicalCoreCount() == services.activeCoreCount())
                .toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No exact required scenario is runnable");
        }
        return result;
    }

    private static TreeSet<SourceScenario> awaiting(ClosedLoopConfig config, ClosedLoopCheckpoint checkpoint) {
        Set<SourceScenario> complete = checkpoint.evidence().stream()
                .filter(entry -> entry.source() == EvidenceSource.BOOTSTRAP)
                .map(EvidenceIndexEntry::scenario)
                .collect(java.util.stream.Collectors.toSet());
        TreeSet<SourceScenario> result = new TreeSet<>(config.requiredScenarios());
        result.removeAll(complete);
        return result;
    }

    private static ClosedLoopResult result(LoadedCheckpoint loaded, TreeSet<SourceScenario> awaiting) {
        ClosedLoopCheckpoint checkpoint = loaded.checkpoint();
        return new ClosedLoopResult(
                checkpoint.stage(),
                checkpoint.nextIteration(),
                loaded.snapshotDirectory(),
                checkpoint
                        .latestMerge()
                        .map(reference ->
                                resolve(loaded.snapshotDirectory().getParent().getParent(), reference)),
                checkpoint
                        .latestModel()
                        .map(reference ->
                                resolve(loaded.snapshotDirectory().getParent().getParent(), reference)),
                java.util.Collections.unmodifiableSortedSet(awaiting),
                Optional.empty());
    }

    private static List<Path> evidencePaths(Path workspace, List<EvidenceIndexEntry> evidence) {
        return evidence.stream()
                .sorted(Comparator.comparing(EvidenceIndexEntry::benchmarkRunId))
                .map(entry -> resolve(workspace, entry.bundle()))
                .toList();
    }

    private static ArtifactReference reference(Path workspace, Path artifact) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path target = artifact.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            LOGGER.error("Artifact is outside the closed-loop workspace: target={}, root={}", target, root);
            throw new IllegalArgumentException("Artifact is outside the closed-loop workspace");
        }
        return new ArtifactReference(
                root.relativize(target).toString().replace('\\', '/'), ArtifactFingerprint.sha256(target));
    }

    private static Path resolve(Path workspace, ArtifactReference reference) {
        Path result = workspace
                .toAbsolutePath()
                .normalize()
                .resolve(reference.relativePath())
                .normalize();
        if (!result.startsWith(workspace.toAbsolutePath().normalize())) {
            LOGGER.error("Artifact path escapes workspace: result={}, workspace={}", result, workspace);
            throw new IllegalArgumentException("Artifact path escapes workspace");
        }
        return result;
    }

    private static DataMerger.MergeArtifacts artifacts(Path directory) {
        return new DataMerger.MergeArtifacts(
                directory.resolve("fixed-anchors.csv"),
                directory.resolve("reference-runs.csv"),
                directory.resolve("calibration-report.csv"),
                directory.resolve("scenario-results.csv"),
                directory.resolve("robust-ranking.csv"),
                directory.resolve("coverage-report.csv"),
                directory.resolve("robust-leaders.vectors.csv"),
                directory.resolve("incomplete-policies.vectors.csv"));
    }

    private static void copyFileAtomically(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!ArtifactFingerprint.sha256(source).equals(ArtifactFingerprint.sha256(target))) {
                LOGGER.error("Existing copied input differs: source={}, target={}", source, target);
                throw new IllegalArgumentException("Existing copied input differs");
            }
            return;
        }
        Files.createDirectories(target.getParent());
        Path temp = target.getParent().resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.copy(source, temp);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            LOGGER.error("Atomic input copy failed for target {}", target, error);
            throw new IOException("Atomic input copy is required", error);
        }
    }

    private static void copyDirectoryAtomically(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!ArtifactFingerprint.sha256(source).equals(ArtifactFingerprint.sha256(target))) {
                LOGGER.error("Existing copied artifact differs: source={}, target={}", source, target);
                throw new IllegalArgumentException("Existing copied artifact differs");
            }
            return;
        }
        Files.createDirectories(target.getParent());
        Path temp = target.getParent().resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createDirectory(temp);
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                if (Files.isSymbolicLink(path)) {
                    LOGGER.error("Input artifact contains symlink: path={}", path);
                    throw new IllegalArgumentException("Input artifacts must not contain symlinks");
                }
                Path relative = source.relativize(path);
                Path destination = temp.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(path, destination);
                }
            }
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            LOGGER.error("Atomic artifact copy failed for target {}", target, error);
            throw new IOException("Atomic artifact copy is required", error);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record StagedInitialCalibrationPlan(ClosedLoopConfig config, Optional<Path> snapshotDirectory)
            implements AutoCloseable {

        @Override
        public void close() throws IOException {
            if (snapshotDirectory.isPresent()) {
                deleteRecursively(snapshotDirectory.orElseThrow());
            }
        }
    }

    private static final class ProductionServices implements ClosedLoopServices {
        private final Path stopFile;

        private ProductionServices(Path stopFile) {
            this.stopFile = stopFile;
        }

        @Override
        public CalibrationPlan bootstrapCalibration(DataMerger.CalibrationBootstrapRequest request) throws Exception {
            return DataMerger.bootstrapCalibrationV1(request);
        }

        @Override
        public DataMerger.MergeArtifacts merge(DataMerger.MergeRequest request) throws Exception {
            return DataMerger.merge(request);
        }

        @Override
        public ScenarioTrainingArtifacts train(ScenarioTrainingRequest request) throws Exception {
            return ScenarioModelTrainer.train(request);
        }

        @Override
        public ScenarioConditionedModel loadAcceptedModel(Path modelDirectory, String producingDevice)
                throws Exception {
            return ScenarioConditionedModel.load(modelDirectory, producingDevice);
        }

        @Override
        public BenchmarkRunContext benchmark(
                NativeBenchmarkRunPlan plan, java.util.function.BooleanSupplier stopRequested) throws Exception {
            return BenchmarkRunner.runV1(plan, stopRequested);
        }

        @Override
        public boolean stopRequested() {
            return ClosedLoopRunner.stopRequested(stopFile);
        }
    }

    private static final class InitializationFailure extends RuntimeException {
        private InitializationFailure(Throwable cause) {
            super(cause);
        }
    }

    private ClosedLoopRunner() {}
}
