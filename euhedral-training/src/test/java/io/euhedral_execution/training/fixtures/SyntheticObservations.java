package io.euhedral_execution.training.fixtures;

import io.euhedral_execution.training.data.BenchmarkObservation;
import io.euhedral_execution.training.data.BenchmarkParameters;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.BenchmarkRunDescriptor;
import io.euhedral_execution.training.data.FrameSourceSeed;
import io.euhedral_execution.training.data.ObservationKey;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.EvidenceOrigin;
import io.euhedral_execution.training.data.enums.MeasurementEncoding;
import io.euhedral_execution.training.data.enums.ObservationStatus;
import io.euhedral_execution.training.data.enums.PolicyRole;
import io.euhedral_execution.training.data.io.ObservationBundleWriter;
import io.euhedral_execution.training.merge.VectorStatistics;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregate;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregateStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public final class SyntheticObservations {
    public static final Instant START = Instant.parse("2026-01-02T03:04:05Z");
    public static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private SyntheticObservations() {}

    public static PolicyVector policy(int seed) {
        double[] weights = new double[PolicyVector.WIDTH];
        for (int i = 0; i < weights.length; i++) weights[i] = seed + i / 100.0;
        return PolicyVector.of(weights);
    }

    public static BenchmarkRunDescriptor run(
            String id, SourceScenario scenario, int repetitions, EvidenceOrigin origin, Instant start) {
        List<FrameSourceSeed> seeds = new ArrayList<>();
        for (int i = 0; i < scenario.sourceCount(); i++) {
            seeds.add(new FrameSourceSeed(i, 100 + i, 200 + i));
        }
        return new BenchmarkRunDescriptor(
                1,
                id,
                1,
                "cohort-1",
                scenario,
                origin == EvidenceOrigin.NATIVE ? SHA : "imported-workspace",
                false,
                origin,
                start,
                new BenchmarkParameters(
                        repetitions, 1_000_000_000L, 2_000_000_000L, 128, 3_000_000_000L, true, "ff", seeds));
    }

    public static Path writeSuccessBundle(
            Path directory,
            BenchmarkRunDescriptor run,
            List<PolicyVector> policies,
            Map<PolicyId, double[]> throughputs,
            Set<PolicyId> anchors) {
        try (ObservationBundleWriter writer = ObservationBundleWriter.open(directory, run)) {
            List<ScheduledPolicy> scheduled = new ArrayList<>();
            for (int i = 0; i < policies.size(); i++) {
                PolicyVector policy = policies.get(i);
                Set<PolicyRole> roles = anchors.contains(policy.id())
                        ? Set.of(PolicyRole.FIXED_ANCHOR)
                        : Set.of(PolicyRole.EXPLORATION);
                ScheduledPolicy item = new ScheduledPolicy(i + 1, policy, roles);
                scheduled.add(item);
                writer.registerPolicy(item);
            }
            Instant last = run.startedAt();
            for (ScheduledPolicy policy : scheduled) {
                double[] values = throughputs.get(policy.policy().id());
                for (int repetition = 1; repetition <= run.parameters().expectedRepetitions(); repetition++) {
                    long completed = Math.round(values[repetition - 1]);
                    Instant started = last;
                    Instant ended = started.plusSeconds(1);
                    last = ended;
                    writer.write(new BenchmarkObservation(
                            new ObservationKey(
                                    run.benchmarkRunId(),
                                    run.scenario(),
                                    policy.policy().id(),
                                    repetition),
                            run,
                            policy,
                            ObservationStatus.SUCCESS,
                            MeasurementEncoding.COUNTER_DERIVED,
                            started,
                            ended,
                            OptionalLong.of(1_000_000_000L),
                            OptionalLong.of(completed),
                            OptionalDouble.of((double) completed),
                            ""));
                }
            }
            writer.complete(last);
        }
        return directory;
    }

    public static RunAggregate aggregate(
            PolicyVector policy,
            String runId,
            SourceScenario scenario,
            double[] successes,
            int planned,
            int timeouts,
            int failed,
            int skipped,
            CalibrationRole role,
            Instant start) {
        double[] sorted = successes.clone();
        double p25 = VectorStatistics.quantileType7(sorted, 0.25);
        double median = VectorStatistics.median(sorted);
        double p75 = VectorStatistics.quantileType7(sorted, 0.75);
        double[] logs = Arrays.stream(sorted).map(StrictMath::log).toArray();
        int successCount = successes.length;
        double successRate = successCount / (double) planned;
        SortedSet<PolicyRole> roles = new TreeSet<>(Comparator.comparing(Enum::name));
        roles.add(role == CalibrationRole.ANCHOR ? PolicyRole.FIXED_ANCHOR : PolicyRole.EXPLORATION);
        return new RunAggregate(
                policy,
                new BenchmarkRunContext(
                        run(runId, scenario, planned, EvidenceOrigin.NATIVE, start), start.plusSeconds(planned + 1)),
                roles,
                planned,
                successCount,
                timeouts,
                failed,
                skipped,
                successRate,
                timeouts / (double) planned,
                (failed + skipped) / (double) planned,
                (timeouts + failed + skipped) / (double) planned,
                successCount >= 3 && successRate >= 0.5
                        ? RunAggregateStatus.VALID
                        : successCount < 3
                                ? RunAggregateStatus.INSUFFICIENT_SUCCESSES
                                : RunAggregateStatus.LOW_SUCCESS_FRACTION,
                OptionalDouble.of(p25),
                OptionalDouble.of(median),
                OptionalDouble.of(p75),
                OptionalDouble.of(p75 - p25),
                OptionalDouble.of(
                        VectorStatistics.quantileType7(logs, 0.75) - VectorStatistics.quantileType7(logs, 0.25)));
    }

    public enum CalibrationRole {
        ANCHOR,
        CANDIDATE
    }
}
