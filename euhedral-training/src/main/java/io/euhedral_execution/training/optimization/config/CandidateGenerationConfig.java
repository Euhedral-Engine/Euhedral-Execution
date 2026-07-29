package io.euhedral_execution.training.optimization.config;

import java.util.Arrays;

public record CandidateGenerationConfig(int screenRows, int maximumPredictionRows,
        int[] scoreBandWeights, int cmaWeight, int scoreBandWeight, int directSobolWeight,
        CmaEsConfig cma) {
    public CandidateGenerationConfig {
        if (screenRows <= 0 || maximumPredictionRows <= 0 || scoreBandWeights.length != 10
                || cmaWeight < 0 || scoreBandWeight < 0 || directSobolWeight < 0
                || cmaWeight + scoreBandWeight + directSobolWeight <= 0) {
            throw new IllegalArgumentException("Invalid candidate generation config");
        }
        int sum = 0;
        for (int weight : scoreBandWeights) {
            if (weight < 0) {
                throw new IllegalArgumentException("Score band weights must not be negative");
            }
            sum = Math.addExact(sum, weight);
        }
        if (sum == 0) {
            throw new IllegalArgumentException("Score band weights must be positive");
        }
        scoreBandWeights = Arrays.copyOf(scoreBandWeights, scoreBandWeights.length);
    }

    @Override
    public int[] scoreBandWeights() {
        return Arrays.copyOf(scoreBandWeights, scoreBandWeights.length);
    }

    public static CandidateGenerationConfig defaults() {
        return new CandidateGenerationConfig(2_097_152, 16_384,
                new int[]{1, 1, 1, 1, 2, 2, 3, 5, 8, 16}, 8, 7, 1,
                CmaEsConfig.defaults());
    }
}
