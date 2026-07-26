package io.euhedral_execution.training.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyRankingTest {

    @Test
    void ranksMedianThenStability() {
        double[] baseline = {8, 9, 10, 11, 12};
        double[] faster = {9, 10, 11, 12, 13};
        double[] sameMedianNarrower = {9, 9.5, 10, 10.5, 11};
        double[] sameMedianWide = {7, 8, 10, 12, 13};

        assertThat(PolicyRanking.compare(faster, baseline)).isPositive();
        assertThat(PolicyRanking.compare(sameMedianNarrower, baseline)).isPositive();
        assertThat(PolicyRanking.compare(sameMedianWide, baseline)).isNegative();
    }

    @Test
    void createsExactCumulativeDecilesForDistinctRanks() {
        List<double[]> quantiles = new ArrayList<>();
        for (int rank = 0; rank < 100; rank++) {
            quantiles.add(new double[]{rank - 2, rank - 1, rank, rank + 1, rank + 2});
        }

        double[][] thresholds = PolicyRanking.buildDecileThresholds(quantiles);
        int[] positives = new int[PolicyRanking.ORDINAL_OUTPUTS];
        float[] encoded = new float[PolicyRanking.ORDINAL_OUTPUTS];

        for (double[] sample : quantiles) {
            PolicyRanking.encodeOrdinal(sample, thresholds, encoded, 0);
            for (int output = 0; output < encoded.length; output++) {
                positives[output] += (int) encoded[output];
            }
        }

        assertThat(positives).containsExactly(90, 80, 70, 60, 50, 40, 30, 20, 10);

        PolicyRanking.encodeOrdinal(quantiles.getFirst(), thresholds, encoded, 0);
        assertThat(encoded).containsOnly(0.0f);

        PolicyRanking.encodeOrdinal(quantiles.getLast(), thresholds, encoded, 0);
        assertThat(encoded).containsOnly(1.0f);
    }
}
