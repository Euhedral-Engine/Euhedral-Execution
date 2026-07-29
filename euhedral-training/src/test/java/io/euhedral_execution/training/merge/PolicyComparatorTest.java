package io.euhedral_execution.training.merge;

import static io.euhedral_execution.training.fixtures.SyntheticObservations.policy;
import static io.euhedral_execution.training.merge.ScenarioQualityRankerTest.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.data.MergeRecords;
import io.euhedral_execution.training.merge.data.MergeRecords.RobustPolicySummary;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class PolicyComparatorTest {
    @Test
    void robustSecondBestEverywhereBeatsSpecialists() {
        List<PolicyVector> policies = List.of(policy(1), policy(2), policy(3), policy(4));
        PolicyVector robust = policies.get(3);
        SourceScenario one = SourceScenario.of("host-a", 1, 32);
        SourceScenario two = SourceScenario.of("host-a", 2, 32);
        SourceScenario three = SourceScenario.of("host-a", 4, 32);
        List<MergeRecords.ScenarioResult> rows = new ArrayList<>();
        double[][] values = {{100, 40, 50}, {50, 100, 40}, {40, 50, 100}, {90, 90, 90}};
        List<SourceScenario> scenarios = List.of(one, two, three);
        for (int p = 0; p < policies.size(); p++) for (int s = 0; s < scenarios.size(); s++) {
            rows.add(row(scenarios.get(s), policies.get(p), values[p][s],
                    values[p][s], values[p][s]));
        }
        List<RobustPolicySummary> summaries = ScenarioQualityRanker.summarize(policies,
                ScenarioQualityRanker.assignQualities(rows), new TreeSet<>(scenarios));
        assertThat(summaries.getFirst().policy()).isEqualTo(robust);
        assertThat(summaries.getFirst().worstQuality()).hasValue(2.0 / 3.0);
        assertThat(summaries.subList(1, 4)).allMatch(
                item -> item.worstQuality().orElseThrow() == 0);
    }

    @Test
    void comparesEveryTierLexicographically() {
        PolicyVector first = policy(1);
        PolicyVector second = policy(2);
        assertBetter(summary(first, .2, .3, .4, .2, .2, .2),
                summary(second, .1, .9, .9, 0, 0, 0));
        assertBetter(summary(first, .2, .4, .4, .2, .2, .2),
                summary(second, .2, .3, .9, 0, 0, 0));
        assertBetter(summary(first, .2, .3, .5, .2, .2, .2),
                summary(second, .2, .3, .4, 0, 0, 0));
        assertBetter(summary(first, .2, .3, .4, .1, .2, .2),
                summary(second, .2, .3, .4, .2, 0, 0));
        assertBetter(summary(first, .2, .3, .4, .1, .1, .2),
                summary(second, .2, .3, .4, .1, .2, 0));
        assertBetter(summary(first, .2, .3, .4, .1, .1, .1),
                summary(second, .2, .3, .4, .1, .1, .2));
        RobustPolicySummary a = summary(first, .2, .3, .4, .1, .1, .1);
        RobustPolicySummary b = summary(second, .2, .3, .4, .1, .1, .1);
        assertThat(Integer.signum(PolicyComparator.BEST_FIRST.compare(a, b)))
                .isEqualTo(Integer.signum(first.id().compareTo(second.id())));
    }

    @Test
    void rejectsIncompleteAndPublishedOrderGatesCoverage() {
        RobustPolicySummary complete = summary(policy(1), .1, .1, .1, .1, .1, .1);
        RobustPolicySummary incomplete = new RobustPolicySummary(policy(2), false, 3, 2, 2,
                2.0 / 3.0, OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), new TreeSet<>(),
                new TreeSet<>(), new TreeSet<>());
        assertThatIllegalArgumentException().isThrownBy(
                () -> PolicyComparator.BEST_FIRST.compare(complete, incomplete));
        assertThat(PolicyComparator.PUBLISHED_ORDER.compare(complete, incomplete)).isNegative();
    }

    private static void assertBetter(RobustPolicySummary better, RobustPolicySummary worse) {
        assertThat(PolicyComparator.BEST_FIRST.compare(better, worse)).isNegative();
    }

    private static RobustPolicySummary summary(PolicyVector policy, double worst, double p25,
            double geometric, double mad, double iqr, double nonSuccess) {
        return new RobustPolicySummary(policy, true, 3, 3, 3, 1,
                OptionalDouble.of(worst), OptionalDouble.of(p25), OptionalDouble.of(geometric),
                OptionalDouble.of(mad), OptionalDouble.of(iqr),
                OptionalDouble.of(nonSuccess), OptionalDouble.of(nonSuccess / 2),
                new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
    }
}
