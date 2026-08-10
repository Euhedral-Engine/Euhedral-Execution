package io.euhedral_execution.training.optimization;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.data.BenchmarkParameters;
import io.euhedral_execution.training.data.FrameSourceSeed;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.PolicyRole;
import java.util.List;

public final class SchedulerSeeds {
    private SchedulerSeeds() {}

    public static long hash(String material, long schedulerSeed) {
        return HasherApi.getHash(material, schedulerSeed);
    }

    public static long scoreBandSamplingKey(long seed, int iteration, int band, PolicyId policyId) {
        return hash(
                "score-band-v1\n"
                        + "iteration=" + iteration + "\n"
                        + "band=" + band + "\n"
                        + "policy=" + policyId.canonical() + "\n",
                seed);
    }

    public static long cmaIslandSeed(long seed, int islandIndex) {
        if (islandIndex < 0) {
            throw new IllegalArgumentException("Island index must not be negative");
        }
        return hash("cma-island-v1\n" + "island=" + islandIndex + "\n", seed);
    }

    public static String candidateCohortId(
            String trainingRunId,
            String kind,
            int iteration,
            SourceScenario scenario,
            List<? extends PolicyWithRole> policies,
            long seed) {
        StringBuilder material = new StringBuilder("candidate-cohort-v1\n")
                .append("training_run=")
                .append(trainingRunId)
                .append('\n')
                .append("kind=")
                .append(kind)
                .append('\n')
                .append("iteration=")
                .append(iteration)
                .append('\n')
                .append("scenario=")
                .append(scenario.canonical())
                .append('\n');
        policies.stream()
                .sorted((left, right) -> left.policyId().compareTo(right.policyId()))
                .forEach(policy -> material.append("policy=")
                        .append(policy.policyId().canonical())
                        .append("|role=")
                        .append(policy.role().name())
                        .append('\n'));
        return "c1-" + "%016x".formatted(hash(material.toString(), seed));
    }

    public static String benchmarkRunId(String material, long seed) {
        return "r1-" + "%016x".formatted(hash(material, seed));
    }

    public static String benchmarkRunId(
            String trainingRunId,
            String kind,
            int iteration,
            SourceScenario scenario,
            String cohortId,
            BenchmarkParameters parameters,
            String commitSha,
            boolean dirtyWorkingTree,
            long seed) {
        return benchmarkRunId(
                "benchmark-run-v1\n"
                        + "training_run=" + trainingRunId + "\n"
                        + "kind=" + kind + "\n"
                        + "iteration=" + iteration + "\n"
                        + "scenario=" + scenario.canonical() + "\n"
                        + "cohort=" + cohortId + "\n"
                        + "expected_repetitions=" + parameters.expectedRepetitions() + "\n"
                        + "sample_duration_nanos=" + parameters.sampleDurationNanos() + "\n"
                        + "liveness_timeout_nanos=" + parameters.livenessTimeoutNanos() + "\n"
                        + "frames_per_source=" + parameters.framesPerSource() + "\n"
                        + "reset_timeout_nanos=" + parameters.resetTimeoutNanos() + "\n"
                        + "ordered_frames=" + parameters.orderedFrames() + "\n"
                        + "cpu_set_hex=" + parameters.cpuSetHex() + "\n"
                        + "commit_sha=" + commitSha + "\n"
                        + "dirty_working_tree=" + dirtyWorkingTree + "\n",
                seed);
    }

    public static long trialOrderKey(String cohortId, PolicyId policyId, long seed) {
        return hash("trial-order-v1\n" + "cohort=" + cohortId + "\n" + "policy=" + policyId.canonical() + "\n", seed);
    }

    public static FrameSourceSeed frameSourceSeed(String benchmarkRunId, int sourceIndex, long seed) {
        if (sourceIndex < 0) {
            throw new IllegalArgumentException("Source index must not be negative");
        }
        long idHash = hash("frame-id-v1\n" + "run=" + benchmarkRunId + "\n" + "source=" + sourceIndex + "\n", seed);
        long routingSeed =
                hash("frame-routing-v1\n" + "run=" + benchmarkRunId + "\n" + "source=" + sourceIndex + "\n", seed);
        return new FrameSourceSeed(sourceIndex, idHash, routingSeed);
    }

    public interface PolicyWithRole {
        PolicyId policyId();

        PolicyRole role();
    }
}
