package io.euhedral_execution.training.optimization;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyRole;
import io.euhedral_execution.training.data.SourceScenario;
import java.util.List;

public final class SchedulerSeeds {
    public static long hash(String material, long schedulerSeed) {
        return HasherApi.getHash(material, schedulerSeed);
    }

    public static long scoreBandSamplingKey(long seed, int iteration, int band,
            PolicyId policyId) {
        return hash("phase3-score-band-v1\n"
                + "iteration=" + iteration + "\n"
                + "band=" + band + "\n"
                + "policy=" + policyId.canonical() + "\n", seed);
    }

    public static String candidateCohortId(String trainingRunId, String kind, int iteration,
            SourceScenario scenario, List<? extends PolicyWithRole> policies, long seed) {
        StringBuilder material = new StringBuilder("phase3-candidate-cohort-v1\n")
                .append("training_run=").append(trainingRunId).append('\n')
                .append("kind=").append(kind).append('\n')
                .append("iteration=").append(iteration).append('\n')
                .append("scenario=").append(scenario.canonical()).append('\n');
        policies.stream().sorted((left, right) -> left.policyId().compareTo(right.policyId()))
                .forEach(policy -> material.append("policy=")
                        .append(policy.policyId().canonical()).append("|role=")
                        .append(policy.role().name()).append('\n'));
        return "c1-" + Long.toUnsignedString(hash(material.toString(), seed), 16);
    }

    public static String benchmarkRunId(String material, long seed) {
        return "r1-" + Long.toUnsignedString(hash(material, seed), 16);
    }

    public interface PolicyWithRole {
        PolicyId policyId();
        PolicyRole role();
    }

    private SchedulerSeeds() {
    }
}
