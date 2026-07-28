package io.euhedral_execution.training.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.merge.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.scheduling.fixtures.Phase3Fixtures;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CmaEsOptimizerTest {

    @Test
    void producesDeterministicNormalizedPoliciesInProductionOrder() {
        List<RobustPolicySummary> measured = new ArrayList<>();
        for (int row = 0; row < 80; row++) {
            measured.add(Phase3Fixtures.eligible(Phase3Fixtures.policy(row),
                    1.0 - row / 100.0));
        }
        CmaEsConfig config = new CmaEsConfig(true, 2, 3, 16, 0.20, 10);
        CmaEsOptimizer optimizer = new CmaEsOptimizer();

        List<PredictedCandidate> first = optimizer.optimize(measured, Set.of(),
                Phase3Fixtures.predictor(), config, 99L);
        Collections.reverse(measured);
        List<PredictedCandidate> permuted = optimizer.optimize(measured, Set.of(),
                Phase3Fixtures.predictor(), config, 99L);

        assertThat(first).hasSize(96);
        assertThat(first.stream().map(candidate -> candidate.policy().id()).toList())
                .containsExactlyElementsOf(permuted.stream()
                        .map(candidate -> candidate.policy().id()).toList());
        assertThat(first).allSatisfy(candidate -> {
            assertThat(candidate.origin()).isEqualTo(CandidateOrigin.CMA_ES);
            assertThat(candidate.prediction().policy())
                    .isSameAs(candidate.policy());
            for (int chunk = 0; chunk < 4; chunk++) {
                double norm = 0.0;
                for (int i = 0; i < 7; i++) {
                    double value = candidate.policy().weight(chunk * 7 + i);
                    assertThat(Double.isFinite(value)).isTrue();
                    norm += value * value;
                }
                assertThat(StrictMath.sqrt(norm)).isCloseTo(1.0,
                        org.assertj.core.data.Offset.offset(1.0e-9));
            }
        });
    }

    @Test
    void excludesAnchorsAndRequiresTheConfiguredSeedMinimum() {
        PolicyVector anchor = Phase3Fixtures.policy(0);
        List<RobustPolicySummary> measured = List.of(
                Phase3Fixtures.eligible(anchor, 1.0),
                Phase3Fixtures.eligible(Phase3Fixtures.policy(1), 0.9));
        CmaEsConfig config = new CmaEsConfig(true, 1, 1, 8, 0.20, 2);

        assertThat(new CmaEsOptimizer().optimize(measured, Set.of(anchor.id()),
                Phase3Fixtures.predictor(), config, 1L)).isEmpty();
    }
}
