package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.BenchmarkParameters;
import io.euhedral_execution.training.data.FrameSourceSeed;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.PolicyRole;
import io.euhedral_execution.training.optimization.SchedulerSeeds;
import io.euhedral_execution.training.scheduling.data.IterationSchedule;
import io.euhedral_execution.training.scheduling.data.ScenarioBudgetReport;
import io.euhedral_execution.training.scheduling.data.ScheduledRun;
import io.euhedral_execution.training.scheduling.enums.RunKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class BootstrapScheduler {
    public static IterationSchedule create(String trainingRunId, SourceScenario scenario,
            List<PolicyVector> bootstrapPolicies, long schedulerSeed, long unchangedSobolCursor,
            String commitSha, boolean dirtyWorkingTree, String cpuSetHex,
            BenchmarkExecutionConfig benchmarkConfig) {
        ArrayList<ScheduledPolicy> policies = new ArrayList<>();
        for (int i = 0; i < bootstrapPolicies.size(); i++) {
            policies.add(new ScheduledPolicy(i + 1, bootstrapPolicies.get(i),
                    Set.of(PolicyRole.EXPLORATION)));
        }
        List<BootstrapIdentity> identityPolicies = policies.stream().map(policy ->
                new BootstrapIdentity(policy.policy().id(), PolicyRole.EXPLORATION)).toList();
        String cohort = SchedulerSeeds.candidateCohortId(trainingRunId,
                RunKind.BOOTSTRAP.name(), 0, scenario, identityPolicies, schedulerSeed);
        BenchmarkParameters identityParameters = new BenchmarkParameters(
                benchmarkConfig.expectedRepetitions(), benchmarkConfig.sampleDurationNanos(),
                benchmarkConfig.livenessTimeoutNanos(), benchmarkConfig.framesPerSource(),
                benchmarkConfig.resetTimeoutNanos(), benchmarkConfig.orderedFrames(), cpuSetHex,
                java.util.stream.IntStream.range(0, scenario.sourceCount())
                        .mapToObj(index -> new FrameSourceSeed(index, 0, 0)).toList());
        String run = SchedulerSeeds.benchmarkRunId(trainingRunId, RunKind.BOOTSTRAP.name(), 0,
                scenario, cohort, identityParameters, commitSha, dirtyWorkingTree, schedulerSeed);
        List<FrameSourceSeed> seeds = java.util.stream.IntStream.range(0, scenario.sourceCount())
                .mapToObj(index -> SchedulerSeeds.frameSourceSeed(run, index, schedulerSeed))
                .toList();
        BenchmarkParameters parameters = new BenchmarkParameters(
                benchmarkConfig.expectedRepetitions(), benchmarkConfig.sampleDurationNanos(),
                benchmarkConfig.livenessTimeoutNanos(), benchmarkConfig.framesPerSource(),
                benchmarkConfig.resetTimeoutNanos(), benchmarkConfig.orderedFrames(), cpuSetHex,
                seeds);
        return new IterationSchedule(trainingRunId, 0,
                List.of(new ScheduledRun(RunKind.BOOTSTRAP, scenario,
                run, cohort, parameters, policies)), List.of(), List.of(),
                List.of(new ScenarioBudgetReport(scenario, policies.size(), 0, 0, 0, 0, 0, 0,
                        0, 0, policies.size(), policies.size(), 0, 0, 0, policies.size())),
                unchangedSobolCursor);
    }

    private BootstrapScheduler() {
    }

    private record BootstrapIdentity(PolicyId policyId, PolicyRole role)
            implements SchedulerSeeds.PolicyWithRole {
    }
}
