package io.euhedral_execution.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.benchmark.data.NativeBenchmarkRunPlan;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.enums.ObservationStatus;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.scheduling.BootstrapScheduler;
import io.euhedral_execution.training.scheduling.fixtures.SchedulingFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkRunnerV1Test {
    @TempDir
    Path temp;

    @Test
    void writesStrictStatusesOnlyWhilePausedAndPublishesAtomically() throws Exception {
        long seed = 91L;
        BenchmarkExecutionConfig config = new BenchmarkExecutionConfig(2, 100, 50, 8,
                1_000, false);
        var schedule = BootstrapScheduler.create("training", SchedulingFixtures.S1,
                List.of(SchedulingFixtures.policy(1), SchedulingFixtures.policy(2)), seed, 0,
                "0".repeat(40), false, "f", config);
        var run = schedule.runs().getFirst();
        Path output = temp.resolve("evidence").resolve(run.benchmarkRunId());
        NativeBenchmarkRunPlan plan = new NativeBenchmarkRunPlan("training", 0,
                run.benchmarkRunId(), run.candidateCohortId(), run.scenario(), run.policies(),
                config, run.parameters(), seed, "0".repeat(40), false, output);
        FakeBackend backend = new FakeBackend();
        BenchmarkRunner.runV1(plan, () -> false, backend, new FakeTime());

        var bundle = ObservationBundleReader.read(output);
        assertThat(bundle.observations()).extracting(observation -> observation.status())
                .containsExactly(ObservationStatus.SUCCESS, ObservationStatus.TIMEOUT,
                        ObservationStatus.FAILED, ObservationStatus.SKIPPED);
        assertThat(bundle.observations().getFirst().throughputFramesPerSecond()
                .orElseThrow()).isEqualTo(5_000_000_000.0);
        assertThat(backend.pauseCount).isEqualTo(2);
        assertThat(Files.isRegularFile(output.resolve("COMPLETE"))).isTrue();
    }

    @Test
    void retainsIncompleteAttemptOnIsolationFailure() {
        long seed = 91L;
        BenchmarkExecutionConfig config = new BenchmarkExecutionConfig(1, 100, 50, 8,
                1_000, false);
        var schedule = BootstrapScheduler.create("training", SchedulingFixtures.S1,
                List.of(SchedulingFixtures.policy(1)), seed, 0, "0".repeat(40), false, "f",
                config);
        var run = schedule.runs().getFirst();
        Path output = temp.resolve("evidence").resolve(run.benchmarkRunId());
        NativeBenchmarkRunPlan plan = new NativeBenchmarkRunPlan("training", 0,
                run.benchmarkRunId(), run.candidateCohortId(), run.scenario(), run.policies(),
                config, run.parameters(), seed, "0".repeat(40), false, output);
        BenchmarkRunner.BenchmarkBackend failing = new FakeBackend() {
            @Override
            public void beginPolicy(ScheduledPolicy policy) {
                throw new IllegalStateException("isolation");
            }
        };
        assertThatThrownBy(() -> BenchmarkRunner.runV1(plan, () -> false, failing,
                new FakeTime())).isInstanceOf(IllegalStateException.class);
        assertThat(output).doesNotExist();
        assertThat(temp.resolve("evidence").toFile().listFiles(file ->
                file.getName().startsWith("." + run.benchmarkRunId() + ".attempt-")))
                .isNotEmpty();
    }

    private static class FakeBackend implements BenchmarkRunner.BenchmarkBackend {
        int policy;
        int repetition;
        int pauseCount;
        boolean paused = true;

        @Override
        public void beginPolicy(ScheduledPolicy ignored) {
            policy++;
            repetition = 0;
            paused = false;
        }

        @Override
        public BenchmarkRunner.Measurement measure(long sampleNanos, long livenessNanos,
                BenchmarkRunner.TimeSource time)
                throws BenchmarkRunner.PolicyMeasurementException {
            repetition++;
            if (policy == 1 && repetition == 1) {
                return new BenchmarkRunner.Measurement(ObservationStatus.SUCCESS, 10, 50,
                        time.instant(), "");
            }
            if (policy == 1) {
                return new BenchmarkRunner.Measurement(ObservationStatus.TIMEOUT, 10, 0,
                        time.instant(), "NO_PROGRESS");
            }
            throw new BenchmarkRunner.PolicyMeasurementException(
                    new IllegalStateException("measurement"));
        }

        @Override
        public void pause() {
            pauseCount++;
            paused = true;
        }

        @Override
        public boolean paused() {
            return paused;
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeTime implements BenchmarkRunner.TimeSource {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Instant instant() {
            return Instant.EPOCH.plusSeconds(calls.getAndIncrement());
        }

        @Override
        public long nanoTime() {
            return calls.getAndIncrement();
        }

        @Override
        public void parkNanos(long nanos) {
        }
    }
}
