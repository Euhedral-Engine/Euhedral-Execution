package io.euhedral_execution.training.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.learning.data.PolicyPredictionCurve;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.scheduling.fixtures.SchedulingFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

class PredictedPolicyComparatorTest {
    @Test
    void robustPolicyBeatsSpecialistsAndAuditOrderDoesNotPromoteThem() {
        var robust = SchedulingFixtures.prediction(SchedulingFixtures.policy(1), 0.72, 0.72, 0.72);
        var first = SchedulingFixtures.prediction(SchedulingFixtures.policy(2), 0.99, 0.70, 0.70);
        var second = SchedulingFixtures.prediction(SchedulingFixtures.policy(3), 0.70, 0.99, 0.70);
        var third = SchedulingFixtures.prediction(SchedulingFixtures.policy(4), 0.70, 0.70, 0.99);

        assertThat(List.of(first, second, third, robust).stream()
                        .sorted(PredictedPolicyComparator.BEST_FIRST)
                        .toList()
                        .getFirst())
                .isEqualTo(robust);
        assertThat(PredictedPolicyComparator.AUDIT_FIRST).isNotSameAs(PredictedPolicyComparator.BEST_FIRST);
    }

    @Test
    void usesGeometricEpsilonAndRejectsIncompleteCurves() {
        var policy = SchedulingFixtures.policy(5);
        var zero = SchedulingFixtures.prediction(policy, 0.0, 0.0, 0.0);
        assertThat(zero.predictedGeometricMeanQuality())
                .isCloseTo(1.0e-12, org.assertj.core.data.Offset.offset(1.0e-27));

        ScenarioPrediction only = new ScenarioPrediction(SchedulingFixtures.S1, 0.5, 0.1, 0.4, 0.6, 0.2, 0.5, 0.1, 0.1);
        assertThatThrownBy(() -> PredictedPolicyRanker.summarize(
                        new PolicyPredictionCurve(policy, List.of(only)), SchedulingFixtures.SCENARIOS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
