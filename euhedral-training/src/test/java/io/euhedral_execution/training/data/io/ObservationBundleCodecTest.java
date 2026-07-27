package io.euhedral_execution.training.data.io;

import static io.euhedral_execution.training.fixtures.SyntheticObservations.*;
import static org.assertj.core.api.Assertions.*;

import io.euhedral_execution.training.data.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObservationBundleCodecTest {
    @TempDir Path temporary;

    @Test
    void roundTripsCompleteFivePolicyBundleDeterministically() throws Exception {
        Path first = writeMixed(temporary.resolve("first"));
        Path second = writeMixed(temporary.resolve("second"));
        ObservationBundle bundle = ObservationBundleReader.read(first);
        assertThat(bundle.policies()).hasSize(5);
        assertThat(bundle.observations()).hasSize(25);
        assertThat(bundle.observations()).extracting(BenchmarkObservation::status)
                .contains(ObservationStatus.SUCCESS, ObservationStatus.TIMEOUT,
                        ObservationStatus.FAILED, ObservationStatus.SKIPPED);
        assertThat(bundle.run().descriptor().scenario().canonical())
                .isEqualTo("s1-host-a-src2-core8-r1of4");
        assertThat(bundle.run().descriptor()).satisfies(run -> {
            assertThat(run.candidateCohortId()).isEqualTo("cohort-1");
            assertThat(run.commitSha()).isEqualTo(SHA);
            assertThat(run.closedLoopIteration()).isEqualTo(1);
            assertThat(run.parameters().frameSourceSeeds()).hasSize(2);
            assertThat(run.parameters().sampleDurationNanos()).isEqualTo(1_000_000_000L);
        });
        assertThat(bundle.policies().getFirst().roles())
                .containsExactlyInAnyOrder(PolicyRole.FIXED_ANCHOR, PolicyRole.EXPLORATION);
        assertThat(bundle.policies().getFirst().policy().copyWeights())
                .containsExactly(policy(1).copyWeights());
        for (String file : List.of("run.csv", "policies.csv", "observations.csv", "COMPLETE")) {
            assertThat(Files.readAllBytes(first.resolve(file)))
                    .isEqualTo(Files.readAllBytes(second.resolve(file)));
        }
    }

    @Test
    void readsTinyGoldenBundle() throws Exception {
        Path golden = Path.of(Objects.requireNonNull(getClass().getResource(
                "/robust-training/v1/golden-bundle")).toURI());
        ObservationBundle bundle = ObservationBundleReader.read(golden);
        assertThat(bundle.policies()).hasSize(1);
        assertThat(bundle.observations()).hasSize(1);
        assertThat(bundle.policies().getFirst().policy().id().canonical())
                .isEqualTo("p1-aa6c38822d778c7c");
    }

    @Test
    void acceptsImportedDirectThroughputButRejectsNative() {
        SourceScenario scenario = SourceScenario.of("host-a", 1, 8);
        PolicyVector policy = policy(1);
        BenchmarkRunDescriptor imported = run("import-1", scenario, 1,
                EvidenceOrigin.IMPORTED, START);
        ScheduledPolicy scheduled = new ScheduledPolicy(1, policy, Set.of(PolicyRole.EXPLORATION));
        BenchmarkObservation direct = new BenchmarkObservation(new ObservationKey("import-1",
                scenario, policy.id(), 1), imported, scheduled, ObservationStatus.SUCCESS,
                MeasurementEncoding.DIRECT_THROUGHPUT, START, START.plusSeconds(1),
                OptionalLong.empty(), OptionalLong.empty(), OptionalDouble.of(123.5), "");
        try (ObservationBundleWriter writer = ObservationBundleWriter.open(
                temporary.resolve("imported"), imported)) {
            writer.registerPolicy(scheduled);
            writer.write(direct);
            writer.complete(START.plusSeconds(1));
        }
        assertThat(ObservationBundleReader.read(temporary.resolve("imported")).observations()
                .getFirst().throughputFramesPerSecond()).hasValue(123.5);
        BenchmarkRunDescriptor nativeRun = run("native-1", scenario, 1,
                EvidenceOrigin.NATIVE, START);
        assertThatIllegalArgumentException().isThrownBy(() -> new BenchmarkObservation(
                new ObservationKey("native-1", scenario, policy.id(), 1), nativeRun, scheduled,
                ObservationStatus.SUCCESS, MeasurementEncoding.DIRECT_THROUGHPUT,
                START, START.plusSeconds(1), OptionalLong.empty(), OptionalLong.empty(),
                OptionalDouble.of(123.5), ""));
    }

    @Test
    void rejectsTamperingAndIncompleteBundles() throws Exception {
        Path original = writeMixed(temporary.resolve("original"));
        assertTamperRejected(original, "scenario", "run.csv",
                text -> text.replace("s1-host-a-src2-core8-r1of4", "s1-host-b-src2-core8-r1of4"));
        assertTamperRejected(original, "hash", "policies.csv",
                text -> text.replaceFirst("p1-[0-9a-f]{16}", "p1-0000000000000000"));
        assertTamperRejected(original, "observation", "observations.csv",
                text -> text.replaceFirst("ob1/", "ob1/bad/"));
        assertTamperRejected(original, "throughput", "observations.csv",
                text -> text.replaceFirst(",101\\.0,", ",999.0,"));
        assertTamperRejected(original, "duration", "observations.csv",
                text -> text.replaceFirst("2026-01-02T03:04:06Z", "2026-01-02T03:04:07Z"));
        assertTamperRejected(original, "schema", "observations.csv",
                text -> text.replaceFirst("(?m)^1,", "2,"));
        assertTamperRejected(original, "duplicate", "observations.csv", text -> {
            int firstLineEnd = text.indexOf('\n');
            int firstDataEnd = text.indexOf('\n', firstLineEnd + 1);
            String firstData = text.substring(firstLineEnd + 1, firstDataEnd + 1);
            return text.substring(0, firstDataEnd + 1) + firstData
                    + text.substring(firstDataEnd + 1);
        });
        assertTamperRejected(original, "missing", "observations.csv", text -> {
            int lastDataStart = text.lastIndexOf('\n', text.length() - 2) + 1;
            return text.substring(0, lastDataStart);
        });
        Path incomplete = copy(original, temporary.resolve("incomplete"));
        Files.delete(incomplete.resolve("COMPLETE"));
        assertThatIllegalArgumentException().isThrownBy(() -> ObservationBundleReader.read(incomplete));
        Path nonempty = copy(original, temporary.resolve("nonempty-marker"));
        Files.writeString(nonempty.resolve("COMPLETE"), "not-empty");
        assertThatIllegalArgumentException().isThrownBy(() -> ObservationBundleReader.read(nonempty));
    }

    @Test
    void rejectsOutOfOrderWritesAndMissingGrid() {
        SourceScenario scenario = SourceScenario.of("host-a", 1, 8);
        BenchmarkRunDescriptor descriptor = run("order-1", scenario, 2,
                EvidenceOrigin.NATIVE, START);
        PolicyVector policy = policy(1);
        ScheduledPolicy second = new ScheduledPolicy(2, policy, Set.of(PolicyRole.EXPLORATION));
        try (ObservationBundleWriter writer = ObservationBundleWriter.open(
                temporary.resolve("order"), descriptor)) {
            assertThatIllegalStateException().isThrownBy(() -> writer.registerPolicy(second));
        }
        ScheduledPolicy first = new ScheduledPolicy(1, policy, Set.of(PolicyRole.EXPLORATION));
        try (ObservationBundleWriter writer = ObservationBundleWriter.open(
                temporary.resolve("grid"), descriptor)) {
            writer.registerPolicy(first);
            writer.write(success(descriptor, first, 1, START, 100));
            assertThatIllegalStateException().isThrownBy(
                    () -> writer.complete(START.plusSeconds(1)));
        }
    }

    private Path writeMixed(Path directory) {
        SourceScenario scenario = SourceScenario.of("host-a", 2, 8);
        BenchmarkRunDescriptor descriptor = run("run-1", scenario, 5,
                EvidenceOrigin.NATIVE, START);
        try (ObservationBundleWriter writer = ObservationBundleWriter.open(directory, descriptor)) {
            List<ScheduledPolicy> policies = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ScheduledPolicy item = new ScheduledPolicy(i + 1, policy(i + 1),
                        i == 0 ? Set.of(PolicyRole.FIXED_ANCHOR, PolicyRole.EXPLORATION)
                                : Set.of(PolicyRole.EXPLORATION));
                policies.add(item);
                writer.registerPolicy(item);
            }
            Instant time = START;
            for (ScheduledPolicy scheduled : policies) for (int repetition = 1; repetition <= 5;
                    repetition++) {
                BenchmarkObservation observation;
                if (scheduled.schedulePosition() == 1 && repetition == 2) {
                    observation = failed(descriptor, scheduled, repetition, time,
                            ObservationStatus.TIMEOUT, "LIVENESS_TIMEOUT");
                } else if (scheduled.schedulePosition() == 1 && repetition == 3) {
                    observation = failed(descriptor, scheduled, repetition, time,
                            ObservationStatus.FAILED, "EXECUTION_FAILED");
                } else if (scheduled.schedulePosition() == 1 && repetition == 4) {
                    observation = skipped(descriptor, scheduled, repetition, time);
                } else {
                    observation = success(descriptor, scheduled, repetition, time,
                            100 + scheduled.schedulePosition());
                }
                writer.write(observation);
                time = observation.endedAt();
            }
            writer.complete(time);
        }
        return directory;
    }

    private static BenchmarkObservation success(BenchmarkRunDescriptor run,
            ScheduledPolicy policy, int repetition, Instant time, long frames) {
        return new BenchmarkObservation(new ObservationKey(run.benchmarkRunId(), run.scenario(),
                policy.policy().id(), repetition), run, policy, ObservationStatus.SUCCESS,
                MeasurementEncoding.COUNTER_DERIVED, time, time.plusSeconds(1),
                OptionalLong.of(1_000_000_000L), OptionalLong.of(frames),
                OptionalDouble.of((double) frames), "");
    }

    private static BenchmarkObservation failed(BenchmarkRunDescriptor run,
            ScheduledPolicy policy, int repetition, Instant time, ObservationStatus status,
            String code) {
        return new BenchmarkObservation(new ObservationKey(run.benchmarkRunId(), run.scenario(),
                policy.policy().id(), repetition), run, policy, status,
                MeasurementEncoding.COUNTER_DERIVED, time, time.plusSeconds(1),
                OptionalLong.of(1_000_000_000L), OptionalLong.of(10),
                OptionalDouble.of(10), code);
    }

    private static BenchmarkObservation skipped(BenchmarkRunDescriptor run,
            ScheduledPolicy policy, int repetition, Instant time) {
        return new BenchmarkObservation(new ObservationKey(run.benchmarkRunId(), run.scenario(),
                policy.policy().id(), repetition), run, policy, ObservationStatus.SKIPPED,
                MeasurementEncoding.COUNTER_DERIVED, time, time, OptionalLong.of(0),
                OptionalLong.of(0), OptionalDouble.empty(), "PREVIOUS_TIMEOUT");
    }

    private void assertTamperRejected(Path original, String name, String file,
            java.util.function.UnaryOperator<String> tamper) throws Exception {
        Path copy = copy(original, temporary.resolve(name));
        Files.writeString(copy.resolve(file), tamper.apply(Files.readString(copy.resolve(file))));
        assertThatIllegalArgumentException().isThrownBy(() -> ObservationBundleReader.read(copy));
    }

    private static Path copy(Path source, Path target) throws IOException {
        Files.createDirectory(target);
        try (var files = Files.list(source)) {
            for (Path file : files.toList()) Files.copy(file, target.resolve(file.getFileName()));
        }
        return target;
    }
}
