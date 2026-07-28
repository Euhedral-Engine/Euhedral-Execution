package io.euhedral_execution.training.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.learning.PolicyPredictionCurve;
import io.euhedral_execution.training.learning.ScenarioPrediction;
import io.euhedral_execution.training.scheduling.fixtures.Phase3Fixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

class PredictedPolicyComparatorTest {
    @Test
    void robustPolicyBeatsSpecialistsAndAuditOrderDoesNotPromoteThem() {
        var robust = Phase3Fixtures.prediction(Phase3Fixtures.policy(1), 0.72, 0.72, 0.72);
        var first = Phase3Fixtures.prediction(Phase3Fixtures.policy(2), 0.99, 0.70, 0.70);
        var second = Phase3Fixtures.prediction(Phase3Fixtures.policy(3), 0.70, 0.99, 0.70);
        var third = Phase3Fixtures.prediction(Phase3Fixtures.policy(4), 0.70, 0.70, 0.99);

        assertThat(List.of(first, second, third, robust).stream()
                .sorted(PredictedPolicyComparator.BEST_FIRST).toList().getFirst())
                .isEqualTo(robust);
        assertThat(PredictedPolicyComparator.AUDIT_FIRST)
                .isNotSameAs(PredictedPolicyComparator.BEST_FIRST);
    }

    @Test
    void usesGeometricEpsilonAndRejectsIncompleteCurves() {
        var policy = Phase3Fixtures.policy(5);
        var zero = Phase3Fixtures.prediction(policy, 0.0, 0.0, 0.0);
        assertThat(zero.predictedGeometricMeanQuality()).isCloseTo(1.0e-12,
                org.assertj.core.data.Offset.offset(1.0e-27));

        ScenarioPrediction only = new ScenarioPrediction(Phase3Fixtures.S1, 0.5, 0.1,
                0.4, 0.6, 0.2, 0.5, 0.1, 0.1);
        assertThatThrownBy(() -> PredictedPolicyRanker.summarize(
                new PolicyPredictionCurve(policy, List.of(only)),
                Phase3Fixtures.SCENARIOS)).isInstanceOf(IllegalArgumentException.class);
    }
}
