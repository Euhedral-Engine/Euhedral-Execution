package io.euhedral_execution.training;

import io.euhedral_execution.training.benchmark.data.NativeBenchmarkRunPlan;
import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import io.euhedral_execution.training.checkpoint.CheckpointSnapshotCodec;
import io.euhedral_execution.training.checkpoint.ClosedLoopConfigFingerprint;
import io.euhedral_execution.training.checkpoint.data.LoadedCheckpoint;
import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.config.ClosedLoopConfig;
import io.euhedral_execution.training.config.ClosedLoopConfigCodec;
import io.euhedral_execution.training.data.BenchmarkObservation;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.BenchmarkRunDescriptor;
import io.euhedral_execution.training.data.ObservationKey;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.data.enums.MeasurementEncoding;
import io.euhedral_execution.training.data.enums.ObservationStatus;
import io.euhedral_execution.training.data.enums.PolicyRole;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.data.io.ObservationBundleWriter;
import io.euhedral_execution.training.learning.AuditScenarioModelFixture;
import io.euhedral_execution.training.learning.ScenarioConditionedModel;
import io.euhedral_execution.training.learning.inputs.ScenarioTrainingRequest;
import io.euhedral_execution.training.learning.output.ScenarioTrainingArtifacts;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.packaging.TrainingRunPackageValidator;
import io.euhedral_execution.training.packaging.TrainingRunPackager;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageRequest;
import io.euhedral_execution.training.packaging.data.TrainingRunPackage;
import io.euhedral_execution.training.packaging.io.TrainingRunPackageInputsCodec;
import io.euhedral_execution.training.scheduling.fixtures.SchedulingFixtures;
import io.euhedral_execution.training.scheduling.io.BootstrapPolicyCsv;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

public final class AuditFixtures {
    public static final String TRAINING_RUN_ID = "phase6-audit";
    public static final long SCHEDULER_SEED = 0x6a09e667f3bcc909L;
    public static final String COMMIT_SHA = "0".repeat(40);
    public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public static Experiment execute(Path temporaryRoot) throws Exception {
        Path root = temporaryRoot.toAbsolutePath().normalize();
        assertIsolated(root);
        Corpus corpus = corpus();

        Path bootstrap = writeBootstrap(root.resolve("bootstrap-policies.vectors.csv"), corpus);

        Path controlWorkspace = root.resolve("control-workspace");
        Path resumedWorkspace = root.resolve("resumed-workspace");
        Path controlPackages = root.resolve("control-packages");
        Path resumedPackages = root.resolve("resumed-packages");
        Path reproducedPackages = root.resolve("reproduced-packages");
        Path rejectedWorkspace = root.resolve("rejected-workspace");
        Path rejectedPackages = root.resolve("rejected-packages");

        ClosedLoopConfig controlAFirst =
                config(root, "control-a-first.conf", controlWorkspace, bootstrap, "audit-a", false);
        ClosedLoopConfig controlB = config(root, "control-b.conf", controlWorkspace, bootstrap, "audit-b", true);
        ClosedLoopConfig controlAFinal =
                config(root, "control-a-final.conf", controlWorkspace, bootstrap, "audit-a", true);
        ClosedLoopConfig resumedAFirst =
                config(root, "resumed-a-first.conf", resumedWorkspace, bootstrap, "audit-a", false);
        ClosedLoopConfig resumedB = config(root, "resumed-b.conf", resumedWorkspace, bootstrap, "audit-b", true);
        ClosedLoopConfig resumedAFinal =
                config(root, "resumed-a-final.conf", resumedWorkspace, bootstrap, "audit-a", true);
        ClosedLoopConfig rejectedAFirst =
                config(root, "rejected-a-first.conf", rejectedWorkspace, bootstrap, "audit-a", false);
        ClosedLoopConfig rejectedB = config(root, "rejected-b.conf", rejectedWorkspace, bootstrap, "audit-b", true);
        String fingerprint = ClosedLoopConfigFingerprint.sha256(controlAFirst);
        for (ClosedLoopConfig item :
                List.of(controlB, controlAFinal, resumedAFirst, resumedB, resumedAFinal, rejectedAFirst, rejectedB)) {
            if (!ClosedLoopConfigFingerprint.sha256(item).equals(fingerprint)) {
                throw new IllegalStateException("Operational config changed frozen fingerprint");
            }
        }

        AuditServices controlServices = new AuditServices(corpus);
        var controlBootstrapA = ClosedLoopRunner.run(controlAFirst, controlServices);
        requireStage(controlBootstrapA.stage(), CheckpointStage.BOOTSTRAP_PENDING);
        controlServices.stopAfterMergeOne = true;
        var controlReady = ClosedLoopRunner.run(controlB, controlServices);
        requireStage(controlReady.stage(), CheckpointStage.READY_TO_TRAIN);
        controlServices.stopAfterMergeOne = false;
        var controlComplete = ClosedLoopRunner.run(controlAFinal, controlServices);
        requireStage(controlComplete.stage(), CheckpointStage.RUN_COMPLETE);
        var controlPackagedResult = ClosedLoopRunner.runAndPackage(controlAFinal, controlServices, controlPackages);
        TrainingRunPackage controlPackage = packageFrom(controlPackagedResult);

        AuditServices resumedServices = new AuditServices(corpus);
        var resumedBootstrapA = ClosedLoopRunner.run(resumedAFirst, resumedServices);
        requireStage(resumedBootstrapA.stage(), CheckpointStage.BOOTSTRAP_PENDING);
        resumedServices.interruptBeforeSecondNormalRun = true;
        var interrupted = ClosedLoopRunner.run(resumedB, resumedServices);
        requireStage(interrupted.stage(), CheckpointStage.BENCHMARKING);
        int interruptedRevision = revision(interrupted.latestCheckpoint());
        TrainingRunPackage interruptedPackage =
                publish(resumedB, interruptedRevision, resumedPackages.resolve("interrupted"));
        String firstNormalFingerprint = resumedServices.firstNormalBundleSha256;
        int completedNormalInvocations = resumedServices.completedNormalInvocations;

        resumedServices.interruptBeforeSecondNormalRun = false;
        resumedServices.stopAfterMergeOne = true;
        var resumedReady = ClosedLoopRunner.run(resumedB, resumedServices);
        requireStage(resumedReady.stage(), CheckpointStage.READY_TO_TRAIN);
        if (resumedServices.completedNormalInvocations != completedNormalInvocations + 1
                || !ArtifactFingerprint.sha256(resumedServices.firstNormalBundle)
                        .equals(firstNormalFingerprint)) {
            throw new IllegalStateException("Resume rewrote or duplicated normal evidence");
        }
        resumedServices.stopAfterMergeOne = false;
        var resumedComplete = ClosedLoopRunner.run(resumedAFinal, resumedServices);
        requireStage(resumedComplete.stage(), CheckpointStage.RUN_COMPLETE);
        var resumedPackagedResult =
                ClosedLoopRunner.runAndPackage(resumedAFinal, resumedServices, resumedPackages.resolve("final"));
        TrainingRunPackage resumedPackage = packageFrom(resumedPackagedResult);

        TrainingRunPackageInputs reproductionInputs = TrainingRunPackageInputsCodec.read(
                resumedPackage.directory().resolve("provenance/package-inputs.properties"));
        TrainingRunPackage reproduced = TrainingRunPackager.publish(
                new TrainingRunPackageRequest(resumedWorkspace, reproducedPackages, reproductionInputs));

        if (!ArtifactFingerprint.sha256(controlPackage.directory())
                        .equals(ArtifactFingerprint.sha256(resumedPackage.directory()))
                || !ArtifactFingerprint.sha256(resumedPackage.directory())
                        .equals(ArtifactFingerprint.sha256(reproduced.directory()))) {
            throw new IllegalStateException("Control, resumed, and reproduced packages differ");
        }

        AuditServices rejectedServices = new AuditServices(corpus);
        rejectedServices.rejectModel = true;
        requireStage(ClosedLoopRunner.run(rejectedAFirst, rejectedServices).stage(), CheckpointStage.BOOTSTRAP_PENDING);
        var rejectedResult = ClosedLoopRunner.runAndPackage(rejectedB, rejectedServices, rejectedPackages);
        requireStage(rejectedResult.stage(), CheckpointStage.MODEL_REJECTED);
        TrainingRunPackage rejectedPackage = packageFrom(rejectedResult);

        return new Experiment(
                root,
                corpus,
                controlWorkspace,
                resumedWorkspace,
                rejectedWorkspace,
                controlPackages,
                resumedPackages,
                reproducedPackages,
                rejectedPackages,
                resumedAFinal,
                rejectedB,
                controlBootstrapA,
                controlReady,
                controlComplete,
                resumedBootstrapA,
                interrupted,
                resumedReady,
                resumedComplete,
                interruptedPackage,
                controlPackage,
                resumedPackage,
                reproduced,
                rejectedResult,
                rejectedPackage,
                resumedServices.failedPolicyId,
                firstNormalFingerprint,
                fingerprint);
    }

    public static Corpus corpus() {
        TreeMap<PolicyId, PolicyMeaning> meanings = new TreeMap<>();
        add(meanings, "A0", 100, 10, -1);
        add(meanings, "A1", 101, 20, -1);
        add(meanings, "A2", 102, 30, -1);
        add(meanings, "A3", 103, 40, -1);
        add(meanings, "A4", 104, 50, -1);
        add(meanings, "R", 105, 90, -1);
        add(meanings, "S0", 106, 5, 0);
        add(meanings, "S1", 107, 5, 1);
        add(meanings, "S2", 108, 5, 2);
        add(meanings, "S3", 109, 5, 3);
        TreeSet<SourceScenario> scenarios = new TreeSet<>(List.of(
                SourceScenario.of("audit-a", 1, 4),
                SourceScenario.of("audit-a", 4, 4),
                SourceScenario.of("audit-b", 1, 4),
                SourceScenario.of("audit-b", 4, 4)));
        return new Corpus(meanings, scenarios);
    }

    public static TrainingRunPackage publish(ClosedLoopConfig config, int revision, Path outputRoot) throws Exception {
        LoadedCheckpoint loaded = CheckpointSnapshotCodec.loadRevision(config.workspace(), revision);
        String packageId = loaded.checkpoint().stage() == CheckpointStage.RUN_COMPLETE
                ? config.trainingRunId()
                : "%s.partial.r%08d".formatted(config.trainingRunId(), revision);
        return TrainingRunPackager.publish(new TrainingRunPackageRequest(
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
    }

    public static List<LoadedCheckpoint> checkpoints(Path workspace) throws Exception {
        ArrayList<LoadedCheckpoint> result = new ArrayList<>();
        for (int revision = 1; ; revision++) {
            try {
                result.add(CheckpointSnapshotCodec.loadRevision(workspace, revision));
            } catch (IllegalArgumentException absent) {
                return List.copyOf(result);
            }
        }
    }

    public static int revision(Path checkpoint) {
        return Integer.parseInt(checkpoint.getFileName().toString().substring("checkpoint-".length()));
    }

    private static void add(
            Map<PolicyId, PolicyMeaning> meanings, String symbol, int seed, double baseline, int specialistScenario) {
        PolicyVector policy = SchedulingFixtures.policy(seed);
        PolicyMeaning previous =
                meanings.put(policy.id(), new PolicyMeaning(symbol, policy, baseline, specialistScenario));
        if (previous != null) {
            throw new IllegalStateException("Audit policy hash collision");
        }
    }

    private static Path writeBootstrap(Path path, Corpus corpus) throws Exception {
        Files.createDirectories(path.getParent());
        ArrayList<String> header = new ArrayList<>(List.of("schema_version", "bootstrap_position", "policy_id"));
        for (int index = 0; index < PolicyVector.WIDTH; index++) {
            header.add("weight_%02d_bits".formatted(index));
        }
        StringBuilder text = new StringBuilder(CanonicalCsv.row(header));
        int position = 1;
        for (PolicyMeaning meaning : corpus.meanings().values()) {
            PolicyVector policy = meaning.policy();
            ArrayList<String> fields = new ArrayList<>(PolicyVector.WIDTH + 3);
            fields.add("1");
            fields.add(Integer.toString(position++));
            fields.add(policy.id().canonical());
            for (int index = 0; index < PolicyVector.WIDTH; index++) {
                fields.add("%016x".formatted(Double.doubleToRawLongBits(policy.weight(index))));
            }
            text.append(CanonicalCsv.row(fields));
        }
        Files.writeString(path, text, StandardCharsets.UTF_8);
        List<PolicyVector> decoded = BootstrapPolicyCsv.read(path, 10);
        if (!decoded.stream()
                .map(PolicyVector::id)
                .toList()
                .equals(List.copyOf(corpus.meanings().keySet()))) {
            throw new IllegalStateException("Bootstrap policy order differs");
        }
        for (PolicyVector policy : decoded) {
            if (!policy.bitwiseEquals(corpus.meanings().get(policy.id()).policy())) {
                throw new IllegalStateException("Bootstrap raw policy lanes differ");
            }
        }
        return path;
    }

    private static ClosedLoopConfig config(
            Path root, String name, Path workspace, Path bootstrap, String environment, boolean resume)
            throws Exception {
        Path file = root.resolve("configs").resolve(name);
        Files.createDirectories(file.getParent());
        Path stop = workspace.resolve("STOP-" + environment);
        StringBuilder text = new StringBuilder();
        line(text, "run.workspace", workspace);
        line(text, "run.training_run_id", TRAINING_RUN_ID);
        line(text, "run.iterations", 2);
        line(text, "run.candidate_budget", 10);
        line(text, "run.active_environment_id", environment);
        line(text, "run.scenarios_per_iteration", 2);
        line(text, "run.scheduler_seed_hex", "6a09e667f3bcc909");
        line(text, "run.initial_sobol_cursor", 131072);
        line(text, "run.bootstrap_policies", bootstrap);
        line(text, "run.commit_sha", COMMIT_SHA);
        line(text, "run.dirty_working_tree", false);
        line(text, "run.resume", resume);
        line(text, "run.stop_file", stop);
        for (SourceScenario scenario : corpus().scenarios()) {
            line(text, "scenario.required", scenario.canonical());
        }
        line(text, "candidate.screen_rows", 64);
        line(text, "candidate.maximum_prediction_rows", 40);
        line(text, "candidate.score_band_weights", "1,1,1,1,1,1,1,1,1,1");
        line(text, "candidate.cma_weight", 0);
        line(text, "candidate.score_band_weight", 0);
        line(text, "candidate.direct_sobol_weight", 1);
        line(text, "candidate.cma.enabled", false);
        line(text, "candidate.cma.islands", 1);
        line(text, "candidate.cma.generations", 1);
        line(text, "candidate.cma.population_size", 8);
        line(text, "candidate.cma.initial_sigma", 0.2);
        line(text, "candidate.cma.minimum_seed_policies", 2);
        line(text, "benchmark.expected_repetitions", 3);
        line(text, "benchmark.sample_duration_nanos", 2000000000L);
        line(text, "benchmark.liveness_timeout_nanos", 50000000L);
        line(text, "benchmark.frames_per_source", 8);
        line(text, "benchmark.reset_timeout_nanos", 1000L);
        line(text, "benchmark.ordered_frames", false);
        line(text, "aggregation.bootstrap_seed_hex", "6a09e667f3bcc909");
        line(text, "training.split_seed_hex", "243f6a8885a308d3");
        line(text, "training.model_seed_hex", "13198a2e03707344");
        line(text, "training.device", "cpu");
        line(text, "training.ensemble_members", 3);
        line(text, "training.loso_evaluation_members", 1);
        line(text, "training.ablation_members", 3);
        line(text, "training.max_epochs", 5);
        line(text, "training.patience", 2);
        line(text, "training.batch_size", 16);
        line(text, "training.minimum_train_policy_groups", 1);
        line(text, "training.minimum_validation_policy_groups", 2);
        line(text, "training.minimum_test_policy_groups", 1);
        line(text, "training.minimum_train_rows_per_scenario", 1);
        line(text, "training.minimum_validation_rows_per_scenario", 1);
        line(text, "training.minimum_test_rows_per_scenario", 1);
        Files.writeString(file, text, StandardCharsets.UTF_8);

        Map<String, String> previous = new HashMap<>();
        for (Map.Entry<String, String> property : Map.of(
                        "cycle.input", "forbidden",
                        "training.device", "gpu9",
                        "euhedral.training.path", "/forbidden")
                .entrySet()) {
            previous.put(property.getKey(), System.getProperty(property.getKey()));
            System.setProperty(property.getKey(), property.getValue());
        }
        try {
            ClosedLoopConfig decoded = ClosedLoopConfigCodec.read(file);
            if (!decoded.workspace().equals(workspace.toAbsolutePath().normalize())
                    || !decoded.activeEnvironmentId().equals(environment)
                    || decoded.resume() != resume
                    || !decoded.stopFile().equals(stop.toAbsolutePath().normalize())) {
                throw new IllegalStateException("Typed audit configuration mapping differs");
            }
            return decoded;
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    private static void line(StringBuilder text, String key, Object value) {
        text.append(key).append('=').append(value).append('\n');
    }

    private static void assertIsolated(Path root) {
        Path repository = Path.of("").toAbsolutePath().normalize();
        for (Path forbidden : List.of(
                repository.resolve("euhedral-training/input"),
                repository.resolve("euhedral-training/output"),
                repository.resolve("data"))) {
            if (root.startsWith(forbidden) || forbidden.startsWith(root)) {
                throw new IllegalArgumentException("Audit temporary root overlaps repository data");
            }
        }
    }

    private static void requireStage(CheckpointStage actual, CheckpointStage expected) {
        if (actual != expected) {
            throw new IllegalStateException("Expected " + expected + " but reached " + actual);
        }
    }

    private static TrainingRunPackage packageFrom(io.euhedral_execution.training.data.ClosedLoopResult result)
            throws Exception {
        return TrainingRunPackageValidator.validate(result.packageDirectory().orElseThrow());
    }

    public record PolicyMeaning(String symbol, PolicyVector policy, double baseline, int specialistScenario) {
        public double throughput(int scenarioOrdinal) {
            return specialistScenario == scenarioOrdinal ? 100 : baseline;
        }
    }

    public record Corpus(SortedMap<PolicyId, PolicyMeaning> meanings, SortedSet<SourceScenario> scenarios) {
        public Corpus {
            meanings = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(meanings));
            scenarios = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(scenarios));
        }

        public PolicyMeaning bySymbol(String symbol) {
            return meanings.values().stream()
                    .filter(item -> item.symbol().equals(symbol))
                    .findFirst()
                    .orElseThrow();
        }

        public int scenarioOrdinal(SourceScenario scenario) {
            int index = 0;
            for (SourceScenario item : scenarios) {
                if (item.equals(scenario)) {
                    return index;
                }
                index++;
            }
            throw new IllegalArgumentException("Unknown audit scenario");
        }
    }

    public record Experiment(
            Path root,
            Corpus corpus,
            Path controlWorkspace,
            Path resumedWorkspace,
            Path rejectedWorkspace,
            Path controlPackages,
            Path resumedPackages,
            Path reproducedPackages,
            Path rejectedPackages,
            ClosedLoopConfig resumedFinalConfig,
            ClosedLoopConfig rejectedConfig,
            io.euhedral_execution.training.data.ClosedLoopResult controlBootstrapA,
            io.euhedral_execution.training.data.ClosedLoopResult controlReady,
            io.euhedral_execution.training.data.ClosedLoopResult controlComplete,
            io.euhedral_execution.training.data.ClosedLoopResult resumedBootstrapA,
            io.euhedral_execution.training.data.ClosedLoopResult interrupted,
            io.euhedral_execution.training.data.ClosedLoopResult resumedReady,
            io.euhedral_execution.training.data.ClosedLoopResult resumedComplete,
            TrainingRunPackage interruptedPackage,
            TrainingRunPackage controlPackage,
            TrainingRunPackage resumedPackage,
            TrainingRunPackage reproducedPackage,
            io.euhedral_execution.training.data.ClosedLoopResult rejected,
            TrainingRunPackage rejectedPackage,
            PolicyId failedPolicyId,
            String firstNormalBundleSha256,
            String configSha256) {}

    private static final class AuditServices implements ClosedLoopServices {
        private final Corpus corpus;
        private boolean stopAfterMergeOne;
        private boolean mergeOneComplete;
        private boolean interruptBeforeSecondNormalRun;
        private boolean interruptionTriggered;
        private boolean rejectModel;
        private int attemptedNormalInvocations;
        private int completedNormalInvocations;
        private PolicyId failedPolicyId;
        private Path firstNormalBundle;
        private String firstNormalBundleSha256;

        private AuditServices(Corpus corpus) {
            this.corpus = corpus;
        }

        @Override
        public CalibrationPlan bootstrapCalibration(DataMerger.CalibrationBootstrapRequest request) throws Exception {
            return DataMerger.bootstrapCalibrationV1(request);
        }

        @Override
        public DataMerger.MergeArtifacts merge(DataMerger.MergeRequest request) throws Exception {
            DataMerger.MergeArtifacts result = DataMerger.merge(request);
            if (request.outputDirectory().getFileName().toString().equals("merge-000001")) {
                mergeOneComplete = true;
            }
            return result;
        }

        @Override
        public ScenarioTrainingArtifacts train(ScenarioTrainingRequest request) throws Exception {
            return rejectModel
                    ? AuditScenarioModelFixture.writeRejected(
                            request.modelDirectory(),
                            request.requiredScenarios(),
                            request.config(),
                            corpus.bySymbol("R").policy(),
                            request.commitSha(),
                            request.dirtyWorkingTree())
                    : AuditScenarioModelFixture.write(
                            request.modelDirectory(),
                            request.requiredScenarios(),
                            request.config(),
                            corpus.bySymbol("R").policy(),
                            request.commitSha(),
                            request.dirtyWorkingTree());
        }

        @Override
        public ScenarioConditionedModel loadAcceptedModel(Path modelDirectory, String producingDevice)
                throws Exception {
            return AuditScenarioModelFixture.open(modelDirectory);
        }

        @Override
        public BenchmarkRunContext benchmark(NativeBenchmarkRunPlan plan, BooleanSupplier stopRequested)
                throws Exception {
            if (plan.iteration() > 0) {
                attemptedNormalInvocations++;
                if (interruptBeforeSecondNormalRun && attemptedNormalInvocations == 2) {
                    interruptionTriggered = true;
                    throw ClosedLoopRunner.stopSignal();
                }
            }
            BenchmarkRunContext result = writeBundle(plan);
            if (plan.iteration() > 0) {
                completedNormalInvocations++;
                if (firstNormalBundle == null) {
                    firstNormalBundle = plan.outputBundle();
                    firstNormalBundleSha256 = ArtifactFingerprint.sha256(firstNormalBundle);
                }
            }
            return result;
        }

        private BenchmarkRunContext writeBundle(NativeBenchmarkRunPlan plan) {
            int scenarioOrdinal = corpus.scenarioOrdinal(plan.scenario());
            Instant runStart = START.plusSeconds(plan.iteration() * 100_000L + scenarioOrdinal * 10_000L);
            BenchmarkRunDescriptor descriptor = new BenchmarkRunDescriptor(
                    1,
                    plan.benchmarkRunId(),
                    plan.iteration(),
                    plan.candidateCohortId(),
                    plan.scenario(),
                    plan.commitSha(),
                    plan.dirtyWorkingTree(),
                    EvidenceOrigin.NATIVE,
                    runStart,
                    plan.parameters());
            PolicyId failure = null;
            if (plan.iteration() == 1 && scenarioOrdinal == 2) {
                failure = plan.policies().stream()
                        .filter(item -> item.roles().contains(PolicyRole.EXPLORATION))
                        .map(item -> item.policy().id())
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
                failedPolicyId = failure;
            }
            Instant completed = runStart;
            try (ObservationBundleWriter writer = ObservationBundleWriter.open(plan.outputBundle(), descriptor)) {
                plan.policies().forEach(writer::registerPolicy);
                for (var scheduled : plan.policies()) {
                    for (int repetition = 1;
                            repetition <= plan.executionConfig().expectedRepetitions();
                            repetition++) {
                        long slot = Math.addExact(
                                Math.multiplyExact(
                                        scheduled.schedulePosition() - 1L,
                                        plan.executionConfig().expectedRepetitions()),
                                repetition - 1L);
                        Instant started = runStart.plusSeconds(slot * 3L);
                        BenchmarkObservation observation;
                        if (scheduled.policy().id().equals(failure)) {
                            observation = failed(descriptor, scheduled, repetition, started);
                        } else {
                            double scale = plan.iteration() == 0
                                    ? 1.0
                                    : plan.scenario().environmentId().equals("audit-b") ? 0.5 : 2.0;
                            PolicyMeaning meaning =
                                    corpus.meanings().get(scheduled.policy().id());
                            double baseline = meaning == null ? 1.0 : meaning.throughput(scenarioOrdinal);
                            double throughput = baseline * scale;
                            long frames = Math.round(throughput * 2.0);
                            Instant ended = started.plusNanos(2_000_000_000L);
                            observation = new BenchmarkObservation(
                                    new ObservationKey(
                                            plan.benchmarkRunId(),
                                            plan.scenario(),
                                            scheduled.policy().id(),
                                            repetition),
                                    descriptor,
                                    scheduled,
                                    ObservationStatus.SUCCESS,
                                    MeasurementEncoding.COUNTER_DERIVED,
                                    started,
                                    ended,
                                    OptionalLong.of(2_000_000_000L),
                                    OptionalLong.of(frames),
                                    OptionalDouble.of(frames * 1_000_000_000.0 / 2_000_000_000L),
                                    "");
                        }
                        writer.write(observation);
                        if (observation.endedAt().isAfter(completed)) {
                            completed = observation.endedAt();
                        }
                    }
                }
                return writer.complete(completed);
            }
        }

        private static BenchmarkObservation failed(
                BenchmarkRunDescriptor descriptor,
                io.euhedral_execution.training.data.ScheduledPolicy scheduled,
                int repetition,
                Instant started) {
            if (repetition == 1) {
                return new BenchmarkObservation(
                        new ObservationKey(
                                descriptor.benchmarkRunId(),
                                descriptor.scenario(),
                                scheduled.policy().id(),
                                repetition),
                        descriptor,
                        scheduled,
                        ObservationStatus.TIMEOUT,
                        MeasurementEncoding.COUNTER_DERIVED,
                        started,
                        started.plusNanos(2_000_000_000L),
                        OptionalLong.of(2_000_000_000L),
                        OptionalLong.of(0),
                        OptionalDouble.of(0),
                        "AUDIT_TIMEOUT");
            }
            return new BenchmarkObservation(
                    new ObservationKey(
                            descriptor.benchmarkRunId(),
                            descriptor.scenario(),
                            scheduled.policy().id(),
                            repetition),
                    descriptor,
                    scheduled,
                    ObservationStatus.SKIPPED,
                    MeasurementEncoding.COUNTER_DERIVED,
                    started,
                    started,
                    OptionalLong.of(0),
                    OptionalLong.of(0),
                    OptionalDouble.empty(),
                    "AFTER_TIMEOUT");
        }

        @Override
        public boolean stopRequested() {
            return stopAfterMergeOne && mergeOneComplete || interruptBeforeSecondNormalRun && interruptionTriggered;
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

    private AuditFixtures() {}
}
