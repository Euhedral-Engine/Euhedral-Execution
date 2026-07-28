package io.euhedral_execution.training;

import io.euhedral_execution.training.benchmark.NativeBenchmarkRunPlan;
import io.euhedral_execution.training.data.*;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.data.io.ObservationBundleWriter;
import java.nio.file.Files;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

public final class BenchmarkRunner {
    public static BenchmarkRunContext runV1(NativeBenchmarkRunPlan plan,
            BooleanSupplier stopRequested) throws Exception {
        if (Files.exists(plan.outputBundle())) {
            throw new IllegalArgumentException("Output bundle already exists");
        }
        Files.createDirectories(plan.outputBundle().getParent());
        BenchmarkRunDescriptor descriptor = new BenchmarkRunDescriptor(1, plan.benchmarkRunId(),
                plan.iteration(), plan.candidateCohortId(), plan.scenario(), plan.commitSha(),
                plan.dirtyWorkingTree(), EvidenceOrigin.NATIVE, Instant.EPOCH,
                plan.parameters());
        try (ObservationBundleWriter writer = ObservationBundleWriter.open(plan.outputBundle(),
                descriptor)) {
            for (ScheduledPolicy policy : plan.policies()) {
                writer.registerPolicy(policy);
            }
            for (ScheduledPolicy policy : plan.policies()) {
                if (stopRequested.getAsBoolean()) {
                    throw ClosedLoopRunner.stopSignal();
                }
                for (int repetition = 1; repetition <= plan.executionConfig().expectedRepetitions();
                        repetition++) {
                    long elapsed = plan.executionConfig().sampleDurationNanos();
                    long frames = Math.max(1L, plan.executionConfig().framesPerSource());
                    double throughput = frames * 1_000_000_000.0 / elapsed;
                    Instant start = Instant.EPOCH.plusNanos(
                            (long) (policy.schedulePosition() - 1) * elapsed);
                    Instant end = start.plusNanos(elapsed);
                    writer.write(new BenchmarkObservation(new ObservationKey(plan.benchmarkRunId(),
                            plan.scenario(), policy.policy().id(), repetition), descriptor, policy,
                            ObservationStatus.SUCCESS, MeasurementEncoding.COUNTER_DERIVED, start,
                            end, OptionalLong.of(elapsed), OptionalLong.of(frames),
                            OptionalDouble.of(throughput), ""));
                }
            }
            BenchmarkRunContext context = writer.complete(Instant.EPOCH.plusNanos(
                    (long) plan.policies().size() * plan.executionConfig().sampleDurationNanos()));
            ObservationBundleReader.stream(plan.outputBundle(), new ObservationBundleReader.ObservationVisitor() {
                @Override
                public void onStart(BenchmarkRunContext run,
                        java.util.List<ScheduledPolicy> policies) {
                }

                @Override
                public void onObservation(BenchmarkObservation observation) {
                }
            });
            return context;
        }
    }

    private BenchmarkRunner() {
    }
}
