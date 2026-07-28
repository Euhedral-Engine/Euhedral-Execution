package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.benchmark.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.*;
import io.euhedral_execution.training.optimization.SchedulerSeeds;
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
        ArrayList<FrameSourceSeed> seeds = new ArrayList<>();
        for (int i = 0; i < scenario.sourceCount(); i++) {
            seeds.add(new FrameSourceSeed(i, SchedulerSeeds.hash("bootstrap-id" + i,
                    schedulerSeed), SchedulerSeeds.hash("bootstrap-route" + i, schedulerSeed)));
        }
        BenchmarkParameters parameters = new BenchmarkParameters(
                benchmarkConfig.expectedRepetitions(), benchmarkConfig.sampleDurationNanos(),
                benchmarkConfig.livenessTimeoutNanos(), benchmarkConfig.framesPerSource(),
                benchmarkConfig.resetTimeoutNanos(), benchmarkConfig.orderedFrames(), cpuSetHex,
                seeds);
        String cohort = "c1-" + Long.toUnsignedString(SchedulerSeeds.hash("bootstrap-cohort\n"
                + trainingRunId + "\n" + scenario.canonical() + "\n", schedulerSeed), 16);
        String run = "r1-" + Long.toUnsignedString(SchedulerSeeds.hash("bootstrap-run\n"
                + cohort + "\n" + commitSha + "\n" + dirtyWorkingTree + "\n", schedulerSeed), 16);
        return new IterationSchedule(0, List.of(new ScheduledRun(RunKind.BOOTSTRAP, scenario,
                run, cohort, parameters, policies)), List.of(), List.of(),
                List.of(new ScenarioBudgetReport(scenario, policies.size(), 0, 0, 0, 0, 0, 0,
                        0, 0, policies.size(), policies.size(), 0, 0, 0, policies.size())),
                unchangedSobolCursor);
    }

    private BootstrapScheduler() {
    }
}
