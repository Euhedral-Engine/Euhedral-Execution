package io.euhedral_execution.training.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.optimization.CmaEsOptimizer.MeasuredPolicy;
import io.euhedral_execution.training.optimization.CmaEsOptimizer.ScoredVector;
import io.euhedral_execution.training.utils.CommonFunctions;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CmaEsOptimizerTest {

    @Test
    void producesFiniteNormalizedPoliciesWithFullVectorScoring() {
        String islands = System.getProperty("candidate.cmaIslands");
        String generations = System.getProperty("candidate.cmaGenerations");
        String population = System.getProperty("candidate.cmaPopulation");
        try {
            System.setProperty("candidate.cmaIslands", "2");
            System.setProperty("candidate.cmaGenerations", "3");
            System.setProperty("candidate.cmaPopulation", "16");

            Random random = new Random(7L);
            List<MeasuredPolicy> measured = new ArrayList<>();
            for (int row = 0; row < 80; row++) {
                double[] vector = new double[28];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = random.nextGaussian();
                }
                CommonFunctions.normalizePolicyVector(vector);
                double quality = vector[0] + vector[7] - vector[14] + 0.5 * vector[21];
                measured.add(new MeasuredPolicy(vector,
                        new double[]{quality - 0.2, quality - 0.1, quality,
                                quality + 0.1, quality + 0.2}));
            }

            CmaEsOptimizer optimizer = new CmaEsOptimizer();
            List<ScoredVector> generated = optimizer.optimize(measured,
                    (features, rows, scores) -> {
                        for (int row = 0; row < rows; row++) {
                            int offset = row * 28;
                            scores[row] = features[offset] + features[offset + 7]
                                    - features[offset + 14] + 0.5f * features[offset + 21];
                        }
                    }, 99L);

            assertThat(generated).hasSize(96);
            for (ScoredVector candidate : generated) {
                assertThat(Float.isFinite(candidate.score())).isTrue();
                for (int chunk = 0; chunk < 4; chunk++) {
                    double norm = 0;
                    for (int i = 0; i < 7; i++) {
                        double value = candidate.vector()[chunk * 7 + i];
                        assertThat(Double.isFinite(value)).isTrue();
                        norm += value * value;
                    }
                    assertThat(Math.sqrt(norm)).isCloseTo(1.0,
                            org.assertj.core.data.Offset.offset(1.0e-9));
                }
            }
        } finally {
            restore("candidate.cmaIslands", islands);
            restore("candidate.cmaGenerations", generations);
            restore("candidate.cmaPopulation", population);
        }
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
