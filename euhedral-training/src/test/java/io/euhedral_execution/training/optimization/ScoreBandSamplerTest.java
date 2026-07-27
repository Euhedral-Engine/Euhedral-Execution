package io.euhedral_execution.training.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.optimization.CmaEsOptimizer.ScoredVector;
import io.euhedral_execution.training.utils.CommonFunctions;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreBandSamplerTest {

    @Test
    void retainsTheConfiguredNumberFromEveryScoreBand() {
        double[] thresholds = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        int[] capacities = ScoreBandSampler.topHeavyCapacities(100);
        ScoreBandSampler sampler = new ScoreBandSampler(thresholds, capacities, 123L);

        for (int i = 0; i < 10_000; i++) {
            int band = i % 10;
            double[] vector = new double[28];
            vector[band % 7] = 1.0;
            vector[7 + ((i / 10) % 7)] = 1.0;
            vector[14 + ((i / 70) % 7)] = 1.0;
            vector[21 + ((i / 490) % 7)] = 1.0;
            vector[(i / 3430) % 7] += i * 1.0e-9;
            CommonFunctions.normalizePolicyVector(vector);
            sampler.accept(vector, band + 0.5f);
        }

        List<ScoredVector> selected = sampler.finish();
        assertThat(selected).hasSize(100);
        for (int band = 0; band < 10; band++) {
            int expected = capacities[band];
            int currentBand = band;
            assertThat(selected.stream()
                    .filter(candidate -> (int) candidate.score() == currentBand)
                    .count()).isEqualTo(expected);
        }
        assertThat(capacities[9]).isGreaterThan(capacities[0]);
    }
}
