package io.euhedral_execution.training.merge;

import io.euhedral_execution.training.data.*;
import io.euhedral_execution.training.data.io.ObservationBundleReader;
import io.euhedral_execution.training.merge.MergeRecords.RunAggregate;
import io.euhedral_execution.training.merge.MergeRecords.RunAggregateStatus;
import java.nio.file.Path;
import java.util.*;

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
            double a = RobustStatistics.quantileType7(values, 0.25);
            double b = RobustStatistics.median(values);
            double c = RobustStatistics.quantileType7(values, 0.75);
            if (!(b > 0) || !Double.isFinite(b)) status = RunAggregateStatus.NONPOSITIVE_THROUGHPUT;
            p25 = OptionalDouble.of(a); median = OptionalDouble.of(b);
            p75 = OptionalDouble.of(c); iqr = OptionalDouble.of(c - a);
            double[] logs = Arrays.stream(values).map(StrictMath::log).toArray();
            logIqr = OptionalDouble.of(RobustStatistics.quantileType7(logs, 0.75)
                    - RobustStatistics.quantileType7(logs, 0.25));
        }
        return new RunAggregate(accumulator.policy, run, accumulator.roles,
                planned, successes.size(), accumulator.timeout, accumulator.failed,
                accumulator.skipped, successRate, accumulator.timeout / (double) planned,
                (accumulator.failed + accumulator.skipped) / (double) planned,
                (accumulator.timeout + accumulator.failed + accumulator.skipped) / (double) planned,
                status,
                p25, median, p75, iqr, logIqr);
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
            return policies.values().stream().map(policy -> aggregate(policy, run, config)).toList();
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
    private RunAggregator() {
    }
}
