package io.euhedral_execution.training;

import io.euhedral_execution.core.config.FragmentActionPicker;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.control_plane.ControlPlaneShard;
import io.euhedral_execution.core.frames.BenchmarkFrame;
import io.euhedral_execution.core.impl.BaseCloneableObject;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.training.benchmark.data.NativeBenchmarkRunPlan;
import io.euhedral_execution.training.data.BenchmarkObservation;
import io.euhedral_execution.training.data.BenchmarkParameters;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.BenchmarkRunDescriptor;
import io.euhedral_execution.training.data.FrameSourceSeed;
import io.euhedral_execution.training.data.ObservationKey;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.data.enums.MeasurementEncoding;
import io.euhedral_execution.training.data.enums.ObservationStatus;
import io.euhedral_execution.training.data.enums.PolicyRole;
import io.euhedral_execution.training.data.io.ObservationBundle;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.data.io.ObservationBundleWriter;
import io.euhedral_execution.training.optimization.SchedulerSeeds;
import io.euhedral_execution.training.utils.BenchmarkFrameSink;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BenchmarkRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkRunner.class);

    private BenchmarkRunner() {}

    public static BenchmarkRunContext runV1(NativeBenchmarkRunPlan plan, BooleanSupplier stopRequested)
            throws Exception {
        validatePlan(plan, true);
        try (BenchmarkBackend backend = NativeBackend.open(plan)) {
            return runV1(plan, stopRequested, backend, SystemTime.INSTANCE);
        }
    }

    static BenchmarkRunContext runV1(
            NativeBenchmarkRunPlan plan, BooleanSupplier stopRequested, BenchmarkBackend backend, TimeSource time)
            throws Exception {
        validatePlan(plan, false);
        Path finalBundle = plan.outputBundle();
        int attempt = nextAttempt(finalBundle);
        Path attemptDirectory = finalBundle.getParent().resolve("." + plan.benchmarkRunId() + ".attempt-" + attempt);
        if (Files.exists(attemptDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Attempt directory already exists");
        }
        Instant runStart = time.instant();
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
        BenchmarkRunContext context;
        try (ObservationBundleWriter writer = ObservationBundleWriter.open(attemptDirectory, descriptor)) {
            for (ScheduledPolicy policy : plan.policies()) {
                writer.registerPolicy(policy);
            }
            int currentP = 0;
            int policies = plan.policies().size();
            for (ScheduledPolicy policy : plan.policies()) {
                LOGGER.info(
                        "Scenario: {} Iteration: {} Policy: {} / {}",
                        plan.scenario().toString(),
                        plan.iteration(),
                        currentP++,
                        policies);
                if (stopRequested.getAsBoolean()) {
                    throw ClosedLoopRunner.stopSignal();
                }
                backend.beginPolicy(policy);
                ArrayList<Measurement> measurements =
                        new ArrayList<>(plan.executionConfig().expectedRepetitions());
                boolean previousTimeout = false;
                boolean previousFailure = false;
                try {
                    for (int repetition = 1;
                            repetition <= plan.executionConfig().expectedRepetitions();
                            repetition++) {
                        if (previousTimeout) {
                            measurements.add(Measurement.skipped("PREVIOUS_TIMEOUT", time.instant()));
                        } else if (previousFailure) {
                            measurements.add(Measurement.skipped("PREVIOUS_FAILURE", time.instant()));
                        } else {
                            try {
                                Measurement measurement = backend.measure(
                                        plan.executionConfig().sampleDurationNanos(),
                                        plan.executionConfig().livenessTimeoutNanos(),
                                        time);
                                measurements.add(measurement);
                                previousTimeout = measurement.status() == ObservationStatus.TIMEOUT;
                            } catch (PolicyMeasurementException error) {
                                measurements.add(Measurement.failed("MEASUREMENT_ERROR", time.instant()));
                                previousFailure = true;
                            }
                        }
                    }
                } finally {
                    backend.pause();
                }
                if (!backend.paused()) {
                    throw new IllegalStateException("Evidence write requires paused sources");
                }
                for (int repetition = 0; repetition < measurements.size(); repetition++) {
                    writer.write(observation(descriptor, policy, repetition + 1, measurements.get(repetition)));
                }
            }
            context = writer.complete(time.instant());
        }
        ObservationBundle validated = ObservationBundleReader.read(attemptDirectory);
        validateBundle(plan, validated);
        try {
            Files.move(attemptDirectory, finalBundle, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            throw new IOException("Atomic benchmark bundle publication is required", error);
        }
        return context;
    }

    private static BenchmarkObservation observation(
            BenchmarkRunDescriptor descriptor, ScheduledPolicy policy, int repetition, Measurement measurement) {
        OptionalLong elapsed = OptionalLong.of(measurement.elapsedNanos());
        OptionalLong frames = OptionalLong.of(measurement.completedFrames());
        OptionalDouble throughput = measurement.elapsedNanos() == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of(measurement.completedFrames() * 1_000_000_000.0 / measurement.elapsedNanos());
        return new BenchmarkObservation(
                new ObservationKey(
                        descriptor.benchmarkRunId(),
                        descriptor.scenario(),
                        policy.policy().id(),
                        repetition),
                descriptor,
                policy,
                measurement.status(),
                MeasurementEncoding.COUNTER_DERIVED,
                measurement.startedAt(),
                measurement.startedAt().plusNanos(measurement.elapsedNanos()),
                elapsed,
                frames,
                throughput,
                measurement.failureCode());
    }

    private static void validatePlan(NativeBenchmarkRunPlan plan, boolean nativeEnvironment) {
        if (Files.exists(plan.outputBundle(), LinkOption.NOFOLLOW_LINKS)
                || plan.outputBundle().getParent() == null
                || !plan.outputBundle().getParent().getFileName().toString().equals("evidence")
                || !plan.commitSha().matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")
                || plan.policies().isEmpty()) {
            throw new IllegalArgumentException("Invalid native benchmark plan");
        }
        Set<PolicyId> ids = new HashSet<>();
        List<SchedulerSeeds.PolicyWithRole> identities = new ArrayList<>();
        for (int i = 0; i < plan.policies().size(); i++) {
            ScheduledPolicy policy = plan.policies().get(i);
            if (policy.schedulePosition() != i + 1
                    || policy.roles().size() != 1
                    || !ids.add(policy.policy().id())) {
                throw new IllegalArgumentException("Invalid scheduled policy identity");
            }
            PolicyRole role = policy.roles().iterator().next();
            identities.add(new RoleIdentity(policy.policy().id(), role));
        }
        String cohort = SchedulerSeeds.candidateCohortId(
                plan.trainingRunId(),
                plan.iteration() == 0 ? "BOOTSTRAP" : "NORMAL",
                plan.iteration(),
                plan.scenario(),
                identities,
                planSchedulerSeed(plan));
        if (!cohort.equals(plan.candidateCohortId())) {
            throw new IllegalArgumentException("Candidate cohort mismatch");
        }
        BenchmarkParameters parameters = plan.parameters();
        if (parameters.expectedRepetitions() != plan.executionConfig().expectedRepetitions()
                || parameters.sampleDurationNanos() != plan.executionConfig().sampleDurationNanos()
                || parameters.livenessTimeoutNanos() != plan.executionConfig().livenessTimeoutNanos()
                || parameters.framesPerSource() != plan.executionConfig().framesPerSource()
                || parameters.resetTimeoutNanos() != plan.executionConfig().resetTimeoutNanos()
                || parameters.orderedFrames() != plan.executionConfig().orderedFrames()
                || parameters.frameSourceSeeds().size() != plan.scenario().sourceCount()) {
            throw new IllegalArgumentException("Benchmark parameters disagree with plan");
        }
        String run = SchedulerSeeds.benchmarkRunId(
                plan.trainingRunId(),
                plan.iteration() == 0 ? "BOOTSTRAP" : "NORMAL",
                plan.iteration(),
                plan.scenario(),
                cohort,
                parameters,
                plan.commitSha(),
                plan.dirtyWorkingTree(),
                planSchedulerSeed(plan));
        if (!run.equals(plan.benchmarkRunId())) {
            throw new IllegalArgumentException("Benchmark run ID mismatch");
        }
        for (int i = 0; i < parameters.frameSourceSeeds().size(); i++) {
            if (!SchedulerSeeds.frameSourceSeed(run, i, planSchedulerSeed(plan))
                    .equals(parameters.frameSourceSeeds().get(i))) {
                throw new IllegalArgumentException("Hidden or changed frame source seed");
            }
        }
        if (nativeEnvironment
                && (SystemInfo.getCoreCount() != plan.scenario().availablePhysicalCoreCount()
                        || !SystemInfo.toHexMask(SystemInfo.getCpuSet()).equals(parameters.cpuSetHex()))) {
            throw new IllegalArgumentException("Active topology does not match exact scenario");
        }
    }

    private static long planSchedulerSeed(NativeBenchmarkRunPlan plan) {
        return plan.schedulerSeed();
    }

    private static void validateBundle(NativeBenchmarkRunPlan plan, ObservationBundle bundle) {
        if (!bundle.run().descriptor().benchmarkRunId().equals(plan.benchmarkRunId())
                || !bundle.run().descriptor().candidateCohortId().equals(plan.candidateCohortId())
                || !bundle.run().descriptor().scenario().equals(plan.scenario())
                || !bundle.policies().equals(plan.policies())) {
            throw new IllegalStateException("Published bundle identity mismatch");
        }
    }

    private static int nextAttempt(Path finalBundle) throws IOException {
        Files.createDirectories(finalBundle.getParent());
        String prefix = "." + finalBundle.getFileName() + ".attempt-";
        try (var stream = Files.list(finalBundle.getParent())) {
            return stream.map(path -> path.getFileName().toString())
                            .filter(name -> name.startsWith(prefix))
                            .mapToInt(name -> Integer.parseInt(name.substring(prefix.length())))
                            .max()
                            .orElse(0)
                    + 1;
        }
    }

    private enum SystemTime implements TimeSource {
        INSTANCE;

        @Override
        public Instant instant() {
            return Instant.now();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void parkNanos(long nanos) {
            LockSupport.parkNanos(nanos);
        }
    }

    interface BenchmarkBackend extends AutoCloseable {
        void beginPolicy(ScheduledPolicy policy) throws Exception;

        Measurement measure(long sampleNanos, long livenessNanos, TimeSource time) throws PolicyMeasurementException;

        void pause() throws Exception;

        boolean paused();

        @Override
        void close() throws Exception;
    }

    interface TimeSource {
        Instant instant();

        long nanoTime();

        void parkNanos(long nanos);
    }

    record Measurement(
            ObservationStatus status, long elapsedNanos, long completedFrames, Instant startedAt, String failureCode) {
        Measurement {
            if (status == null || elapsedNanos < 0 || completedFrames < 0 || startedAt == null || failureCode == null) {
                throw new IllegalArgumentException("Invalid benchmark measurement");
            }
        }

        static Measurement skipped(String reason, Instant instant) {
            return new Measurement(ObservationStatus.SKIPPED, 0, 0, instant, reason);
        }

        static Measurement failed(String reason, Instant instant) {
            return new Measurement(ObservationStatus.FAILED, 0, 0, instant, reason);
        }
    }

    static final class PolicyMeasurementException extends Exception {
        PolicyMeasurementException(Throwable cause) {
            super(cause);
        }
    }

    private static final class NativeBackend implements BenchmarkBackend {
        private final FragmentActionPicker picker;
        private final ControlPlaneLattice lattice;
        private final List<BenchmarkFrameSink> sinks;
        private final Duration resetTimeout;
        private boolean paused = true;

        private NativeBackend(
                FragmentActionPicker picker,
                ControlPlaneLattice lattice,
                List<BenchmarkFrameSink> sinks,
                Duration resetTimeout) {
            this.picker = picker;
            this.lattice = lattice;
            this.sinks = sinks;
            this.resetTimeout = resetTimeout;
        }

        static NativeBackend open(NativeBenchmarkRunPlan plan) {
            ThreadTools.setAffinity(SystemInfo.getCoreInfo(0).getCpuSet().nextSetBit(0));
            FragmentActionPicker picker = new FragmentActionPicker(new double[28]);
            LatticeConfig config = new LatticeConfig(
                    "Training-" + plan.benchmarkRunId(),
                    SystemInfo.getCpuSet(),
                    Duration.ofSeconds(1),
                    ControlPlaneShard.createBaseShard(
                            "Shard", new BaseCloneableObject(FragmentConfig.ofBenchmark(picker))));
            ControlPlaneLattice lattice = ControlPlaneLattice.getOrCreate(config);
            ArrayList<BenchmarkFrameSink> sinks = new ArrayList<>();
            for (FrameSourceSeed seed : plan.parameters().frameSourceSeeds()) {
                BenchmarkFrame[] frames = BenchmarkFrame.generate(
                        plan.parameters().framesPerSource(),
                        plan.parameters().orderedFrames(),
                        seed.idHash(),
                        seed.routingSeed());
                sinks.add(new BenchmarkFrameSink(frames));
            }
            lattice.start();
            sinks.forEach(lattice::addUpstream);
            return new NativeBackend(
                    picker, lattice, sinks, Duration.ofNanos(plan.parameters().resetTimeoutNanos()));
        }

        @Override
        public void beginPolicy(ScheduledPolicy policy) {
            picker.setWeights(new double[28]);
            pauseAll();
            lattice.clear(resetTimeout);
            sinks.forEach(BenchmarkFrameSink::resetCounter);
            picker.setWeights(policy.policy().copyWeights());
            sinks.forEach(BenchmarkFrameSink::resume);
            paused = false;
        }

        @Override
        public Measurement measure(long sampleNanos, long livenessNanos, TimeSource time)
                throws PolicyMeasurementException {
            Instant started = time.instant();
            try {
                long baseline = consumed();
                long previous = baseline;
                long start = time.nanoTime();
                long lastProgress = start;
                while (true) {
                    time.parkNanos(Math.min(livenessNanos, 100_000L));
                    long now = time.nanoTime();
                    long current = consumed();
                    if (current < baseline) {
                        throw new ArithmeticException("Negative counter delta");
                    }
                    if (current != previous) {
                        previous = current;
                        lastProgress = now;
                    }
                    long elapsed = now - start;
                    long frames = current - baseline;
                    if (elapsed >= sampleNanos) {
                        return new Measurement(
                                frames > 0 ? ObservationStatus.SUCCESS : ObservationStatus.TIMEOUT,
                                elapsed,
                                frames,
                                started,
                                frames > 0 ? "" : "ZERO_COMPLETED_FRAMES");
                    }
                    if (now - lastProgress >= livenessNanos) {
                        return new Measurement(ObservationStatus.TIMEOUT, elapsed, frames, started, "NO_PROGRESS");
                    }
                }
            } catch (RuntimeException error) {
                throw new PolicyMeasurementException(error);
            }
        }

        @Override
        public void pause() {
            picker.setWeights(new double[28]);
            pauseAll();
            paused = true;
        }

        @Override
        public boolean paused() {
            return paused;
        }

        @Override
        public void close() {
            picker.setWeights(new double[28]);
            for (BenchmarkFrameSink sink : sinks) {
                try {
                    sink.hardStop(resetTimeout);
                } catch (RuntimeException ignored) {
                    // Close the remaining owners, then surface through lattice close if needed.
                }
            }
            lattice.close();
        }

        private void pauseAll() {
            for (BenchmarkFrameSink sink : sinks) {
                sink.pause(resetTimeout);
            }
        }

        private long consumed() {
            long total = 0;
            for (BenchmarkFrameSink sink : sinks) {
                total = Math.addExact(total, sink.getConsumed());
            }
            return total;
        }
    }

    private record RoleIdentity(PolicyId policyId, PolicyRole role) implements SchedulerSeeds.PolicyWithRole {}
}
