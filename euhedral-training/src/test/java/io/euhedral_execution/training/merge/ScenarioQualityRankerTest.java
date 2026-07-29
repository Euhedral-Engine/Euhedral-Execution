package io.euhedral_execution.training.merge;

import static io.euhedral_execution.training.fixtures.SyntheticObservations.policy;
import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResult;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class ScenarioQualityRankerTest {
    @Test
    void assignsExactMidranksForTies() {
        SourceScenario scenario = SourceScenario.of("host-a", 1, 8);
        List<PolicyVector> policies = List.of(policy(1), policy(2), policy(3), policy(4), policy(5));
        List<ScenarioResult> ranked = ScenarioQualityRanker.assignQualities(List.of(
                row(scenario, policies.get(0), 10, 9, 11),
                row(scenario, policies.get(1), 20, 19, 21),
                row(scenario, policies.get(2), 20, 1, 100),
                row(scenario, policies.get(3), 20, 20, 20),
                row(scenario, policies.get(4), 30, 29, 31)));
        Map<PolicyVector, Double> quality = new HashMap<>();
        ranked.forEach(item -> quality.put(item.policy(), item.quality().orElseThrow()));
        assertThat(policies.stream().map(quality::get).toList())
                .containsExactly(0.0, 0.5, 0.5, 0.5, 1.0);
    }

    @Test
    void singletonAndAllTiedPopulationsAreOneHalf() {
        SourceScenario scenario = SourceScenario.of("host-a", 1, 8);
        assertThat(ScenarioQualityRanker.assignQualities(
                List.of(row(scenario, policy(1), 10, 10, 10))).getFirst().quality()).hasValue(0.5);
        List<ScenarioResult> tied = ScenarioQualityRanker.assignQualities(List.of(
                row(scenario, policy(1), 10, 1, 20),
                row(scenario, policy(2), 10, 9, 11)));
        assertThat(tied).allMatch(item -> item.quality().orElseThrow() == 0.5);
    }

    @Test
    void exactScenarioIdentitySeparatesEqualRatios() {
        PolicyVector low = policy(1);
        PolicyVector high = policy(2);
        SourceScenario small = SourceScenario.of("host-a", 1, 8);
        SourceScenario large = SourceScenario.of("host-a", 2, 16);
        SourceScenario other = SourceScenario.of("host-b", 1, 8);
        List<ScenarioResult> ranked = ScenarioQualityRanker.assignQualities(List.of(
                row(small, low, 10, 10, 10), row(small, high, 20, 20, 20),
                row(large, low, 30, 30, 30), row(other, low, 40, 40, 40)));
        assertThat(ranked.stream().filter(item -> item.scenario().equals(small)
                && item.policy().equals(low)).findFirst().orElseThrow().quality()).hasValue(0);
        assertThat(ranked.stream().filter(item -> item.scenario().equals(large))
                .findFirst().orElseThrow().quality()).hasValue(0.5);
        assertThat(ranked.stream().filter(item -> item.scenario().equals(other))
                .findFirst().orElseThrow().quality()).hasValue(0.5);
    }

    static ScenarioResult row(SourceScenario scenario, PolicyVector policy, double median,
            double low, double high) {
        return new ScenarioResult(scenario, policy, ScenarioResultStatus.VALID_STRONG,
                1, 1, 0, 0, 3, 3, OptionalDouble.of(median), OptionalDouble.of(median),
                OptionalDouble.of(median), OptionalDouble.of(0), OptionalDouble.of(0.1),
                OptionalDouble.of(0), OptionalDouble.of(0), OptionalDouble.of(0),
                OptionalDouble.of(low), OptionalDouble.of(high), OptionalDouble.empty());
    }
}
