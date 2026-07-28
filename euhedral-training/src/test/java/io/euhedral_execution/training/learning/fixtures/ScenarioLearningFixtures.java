package io.euhedral_execution.training.learning.fixtures;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.learning.ScenarioLearningRow;
import io.euhedral_execution.training.learning.ScenarioTrainingConfig;
import io.euhedral_execution.training.merge.MergeRecords.ScenarioResult;
import io.euhedral_execution.training.merge.MergeRecords.ScenarioResultStatus;
import io.euhedral_execution.training.merge.ScenarioQualityRanker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.HashSet;
import java.util.SortedSet;
import java.util.TreeSet;

public final class ScenarioLearningFixtures {
    private ScenarioLearningFixtures() {
    }

    public static List<PolicyVector> policies(int count) {
        if (count == 160) return splitBalancedPolicies();
        ArrayList<PolicyVector> result = new ArrayList<>(count);
        for (int policy = 0; policy < count; policy++) {
            double[] weights = new double[PolicyVector.WIDTH];
            weights[0] = policy / (double) (count - 1);
            weights[1] = ((policy * 73) % count) / (double) (count - 1);
            weights[2] = ((policy * 41 + 17) % count) / (double) (count - 1);
            for (int index = 3; index < weights.length; index++) {
                weights[index] = StrictMath.sin((policy + 1.0) * (index + 3.0)) * 0.5;
            }
            result.add(PolicyVector.of(weights));
        }
        return List.copyOf(result);
    }

    private static List<PolicyVector> splitBalancedPolicies() {
        ArrayList<PolicyVector> train = new ArrayList<>();
        ArrayList<PolicyVector> early = new ArrayList<>();
        ArrayList<PolicyVector> scoreHigh = new ArrayList<>();
        ArrayList<PolicyVector> scoreLow = new ArrayList<>();
        ArrayList<PolicyVector> testHigh = new ArrayList<>();
        ArrayList<PolicyVector> testLow = new ArrayList<>();
        HashSet<io.euhedral_execution.training.data.PolicyId> seen = new HashSet<>();
        Random random = new Random(0x5eed1234L);
        long splitSeed = ScenarioTrainingConfig.defaults().splitSeed();
        while (train.size() < 116 || early.size() < 12 || scoreHigh.size() < 8
                || scoreLow.size() < 4 || testHigh.size() < 8 || testLow.size() < 12) {
            double[] weights = new double[PolicyVector.WIDTH];
            for (int index = 0; index < weights.length; index++) {
                weights[index] = random.nextDouble() * 2 - 1;
            }
            weights[0] = random.nextDouble();
            weights[1] = random.nextDouble();
            weights[2] = random.nextDouble() - 0.5;
            PolicyVector policy = PolicyVector.of(weights);
            if (!seen.add(policy.id())) continue;
            double minimum = Double.POSITIVE_INFINITY;
            double maximum = Double.NEGATIVE_INFINITY;
            for (double ratio : new double[]{0.25, 0.50, 0.75, 1.0}) {
                double latent = (1 - ratio) * policy.weight(0)
                        + ratio * policy.weight(1)
                        + 0.20 * (2 * ratio - 1) * policy.weight(2);
                minimum = StrictMath.min(minimum, latent);
                maximum = StrictMath.max(maximum, latent);
            }
            long splitHash = HasherApi.getHash(policy.id().canonical(), splitSeed);
            int bucket = (int) Math.unsignedMultiplyHigh(splitHash, 10L);
            if (bucket < 8 && train.size() < 116 && maximum < 0.75) {
                train.add(policy);
            } else if (bucket == 8) {
                long halfHash = HasherApi.getHash(policy.id().canonical(),
                        splitSeed ^ 0x9e3779b97f4a7c15L);
                if ((halfHash & 1) == 0 && early.size() < 12 && maximum < 0.30) {
                    early.add(policy);
                } else if ((halfHash & 1) != 0 && minimum > 0.82
                        && scoreHigh.size() < 8) {
                    scoreHigh.add(policy);
                } else if ((halfHash & 1) != 0 && maximum < 0.30
                        && scoreLow.size() < 4) {
                    scoreLow.add(policy);
                }
            } else if (bucket == 9) {
                if (minimum > 0.82 && testHigh.size() < 8) {
                    testHigh.add(policy);
                } else if (maximum < 0.30 && testLow.size() < 12) {
                    testLow.add(policy);
                }
            }
        }
        ArrayList<PolicyVector> result = new ArrayList<>(160);
        result.addAll(train);
        result.addAll(early);
        result.addAll(scoreHigh);
        result.addAll(scoreLow);
        result.addAll(testHigh);
        result.addAll(testLow);
        return List.copyOf(result);
    }

    public static SortedSet<SourceScenario> scenarios() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(List.of(
                SourceScenario.of("host-a", 8, 32),
                SourceScenario.of("host-a", 16, 32),
                SourceScenario.of("host-b", 24, 32),
                SourceScenario.of("host-b", 32, 32))));
    }

    public static List<ScenarioResult> scenarioResults() {
        List<PolicyVector> policies = policies(160);
        ArrayList<ScenarioResult> raw = new ArrayList<>();
        for (SourceScenario scenario : scenarios()) {
            double ratio = scenario.ratio().asDouble();
            for (PolicyVector policy : policies) {
                double latent = (1 - ratio) * policy.weight(0)
                        + ratio * policy.weight(1)
                        + 0.20 * (2 * ratio - 1) * policy.weight(2);
                double throughput = 10_000 + 1_000 * latent;
                raw.add(result(scenario, policy, ScenarioResultStatus.VALID_STRONG,
                        throughput, OptionalDouble.empty()));
            }
        }
        return ScenarioQualityRanker.assignQualities(raw);
    }

    public static List<ScenarioLearningRow> learningRows() {
        ArrayList<ScenarioLearningRow> rows = new ArrayList<>();
        for (ScenarioResult result : scenarioResults()) {
            rows.add(new ScenarioLearningRow(result.policy(), result.scenario(), result.status(),
                    result.quality().orElseThrow(), result.throughputMedian().orElseThrow(),
                    result.bootstrapMedianCiLow().orElseThrow(),
                    result.bootstrapMedianCiHigh().orElseThrow(), result.acceptedRunCount(),
                    result.medianWithinRunRelativeIqr().orElseThrow(),
                    result.meanNonSuccessRate().orElseThrow()));
        }
        rows.sort(null);
        return List.copyOf(rows);
    }

    public static ScenarioResult result(SourceScenario scenario, PolicyVector policy,
            ScenarioResultStatus status, double throughput, OptionalDouble quality) {
        boolean valid = status == ScenarioResultStatus.VALID_STRONG
                || status == ScenarioResultStatus.VALID_WEAK_OVERRIDE;
        OptionalDouble present = valid ? OptionalDouble.of(throughput) : OptionalDouble.empty();
        OptionalDouble zero = valid ? OptionalDouble.of(0) : OptionalDouble.empty();
        OptionalDouble iqr = valid ? OptionalDouble.of(0.05) : OptionalDouble.empty();
        return new ScenarioResult(scenario, policy, status, valid ? 1 : 0, valid ? 1 : 0,
                status == ScenarioResultStatus.VALID_WEAK_OVERRIDE ? 1 : 0,
                valid ? 0 : 1, valid ? 3 : 0, valid ? 3 : 0,
                present, present, present, zero, iqr, zero, zero, zero,
                valid ? OptionalDouble.of(throughput * 0.99) : OptionalDouble.empty(),
                valid ? OptionalDouble.of(throughput * 1.01) : OptionalDouble.empty(),
                quality);
    }
}
