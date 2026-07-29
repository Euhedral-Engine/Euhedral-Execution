package io.euhedral_execution.training.merge;

import io.euhedral_execution.training.data.BenchmarkObservation;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.ObservationKey;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.enums.PolicyRole;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.merge.config.AggregationConfig;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregate;
import io.euhedral_execution.training.merge.data.MergeRecords.RunAggregateStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public final class RunAggregator {

    public static List<RunAggregate> aggregate(List<Path> bundles, PolicyRegistry policies,
            AggregationConfig config) {
        List<Path> sorted = bundles.stream().map(path -> path.toAbsolutePath().normalize())
                .sorted().toList();
        Set<String> runIds = new HashSet<>();
        Set<ObservationKey> keys = new HashSet<>();
        List<RunAggregate> result = new ArrayList<>();
        for (Path path : sorted) {
            BundleAccumulator accumulator = new BundleAccumulator(policies, config, keys);
            ObservationBundleReader.stream(path, accumulator);
            if (!runIds.add(accumulator.run.descriptor().benchmarkRunId())) {
                throw new IllegalArgumentException("Duplicate benchmark run ID");
            }
            result.addAll(accumulator.finish());
        }
        result.sort(Comparator.comparing((RunAggregate row) -> row.run().descriptor().scenario())
                .thenComparing(row -> row.policy().id())
                .thenComparing(row -> row.run().descriptor().benchmarkRunId()));
        return List.copyOf(result);
    }

    private static RunAggregate aggregate(PolicyAccumulator accumulator,
            BenchmarkRunContext run, AggregationConfig config) {
        int planned = run.descriptor().parameters().expectedRepetitions();
        List<Double> successes = accumulator.successes;
        double successRate = successes.size() / (double) planned;
        RunAggregateStatus status = successes.size() < config.minimumSuccessfulRepetitions()
                ? RunAggregateStatus.INSUFFICIENT_SUCCESSES
                : successRate < config.minimumSuccessFraction()
                  ? RunAggregateStatus.LOW_SUCCESS_FRACTION : RunAggregateStatus.VALID;
        OptionalDouble p25 = OptionalDouble.empty(), median = OptionalDouble.empty();
        OptionalDouble p75 = OptionalDouble.empty(), iqr = OptionalDouble.empty();
        OptionalDouble logIqr = OptionalDouble.empty();
        if (!successes.isEmpty()) {
            double[] values = successes.stream().mapToDouble(Double::doubleValue).toArray();
            double a = VectorStatistics.quantileType7(values, 0.25);
            double b = VectorStatistics.median(values);
            double c = VectorStatistics.quantileType7(values, 0.75);
            if (!(b > 0) || !Double.isFinite(b)) {
                status = RunAggregateStatus.NONPOSITIVE_THROUGHPUT;
            }
            p25 = OptionalDouble.of(a);
            median = OptionalDouble.of(b);
            p75 = OptionalDouble.of(c);
            iqr = OptionalDouble.of(c - a);
            double[] logs = Arrays.stream(values).map(StrictMath::log).toArray();
            logIqr = OptionalDouble.of(VectorStatistics.quantileType7(logs, 0.75)
                    - VectorStatistics.quantileType7(logs, 0.25));
        }
        return new RunAggregate(accumulator.policy, run, accumulator.roles,
                planned, successes.size(), accumulator.timeout, accumulator.failed,
                accumulator.skipped, successRate, accumulator.timeout / (double) planned,
                (accumulator.failed + accumulator.skipped) / (double) planned,
                (accumulator.timeout + accumulator.failed + accumulator.skipped) / (double) planned,
                status,
                p25, median, p75, iqr, logIqr);
    }

    private RunAggregator() {
    }

    private static final class BundleAccumulator
            implements ObservationBundleReader.ObservationVisitor {

        private final PolicyRegistry registry;
        private final AggregationConfig config;
        private final Set<ObservationKey> globalKeys;
        private final SortedMap<PolicyId, PolicyAccumulator> policies = new TreeMap<>();
        private BenchmarkRunContext run;

        private BundleAccumulator(PolicyRegistry registry, AggregationConfig config,
                Set<ObservationKey> globalKeys) {
            this.registry = registry;
            this.config = config;
            this.globalKeys = globalKeys;
        }

        @Override
        public void onStart(BenchmarkRunContext run, List<ScheduledPolicy> scheduledPolicies) {
            this.run = run;
            for (ScheduledPolicy scheduled : scheduledPolicies) {
                PolicyVector policy = registry.register(scheduled.policy());
                SortedSet<PolicyRole> roles = new TreeSet<>(Comparator.comparing(Enum::name));
                roles.addAll(scheduled.roles());
                policies.put(policy.id(), new PolicyAccumulator(policy, roles));
            }
        }

        @Override
        public void onObservation(BenchmarkObservation observation) {
            if (!globalKeys.add(observation.key())) {
                throw new IllegalArgumentException("Duplicate observation");
            }
            PolicyAccumulator accumulator = policies.get(observation.key().policyId());
            switch (observation.status()) {
                case SUCCESS -> accumulator.successes.add(
                        observation.throughputFramesPerSecond().orElseThrow());
                case TIMEOUT -> accumulator.timeout++;
                case FAILED -> accumulator.failed++;
                case SKIPPED -> accumulator.skipped++;
            }
        }

        private List<RunAggregate> finish() {
            return policies.values().stream().map(policy -> aggregate(policy, run, config))
                    .toList();
        }
    }

    private static final class PolicyAccumulator {

        private final PolicyVector policy;
        private final SortedSet<PolicyRole> roles;
        private final List<Double> successes = new ArrayList<>();
        private int timeout;
        private int failed;
        private int skipped;

        private PolicyAccumulator(PolicyVector policy, SortedSet<PolicyRole> roles) {
            this.policy = policy;
            this.roles = roles;
        }
    }
}
