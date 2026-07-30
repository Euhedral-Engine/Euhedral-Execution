package io.euhedral_execution.training.legacy;

import io.euhedral_execution.training.utils.CommonFunctions;
import io.euhedral_execution.training.utils.PolicyRanking;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

// ROBUST_OPTIMIZER_POOLED_V0_REMOVAL
final class LegacyCmaEsOptimizer {
    static final int DIMENSIONS = 28;

    @FunctionalInterface
    interface BatchScorer {
        void score(float[] features, int rows, float[] scores);
    }

    record MeasuredPolicy(double[] vector, double[] quantiles) {
        MeasuredPolicy {
            vector = Arrays.copyOf(vector, vector.length);
            quantiles = Arrays.copyOf(quantiles, quantiles.length);
        }
    }

    record ScoredVector(double[] vector, float score) {
        ScoredVector {
            vector = Arrays.copyOf(vector, vector.length);
        }
    }

    List<ScoredVector> optimize(List<MeasuredPolicy> measured, BatchScorer scorer, long seed) {
        if (!Boolean.parseBoolean(System.getProperty("candidate.cmaEnabled", "true"))
                || measured.size() < 10) {
            return List.of();
        }
        List<MeasuredPolicy> ranked = new ArrayList<>(measured);
        ranked.sort((left, right) -> PolicyRanking.compare(
                right.quantiles(), left.quantiles()));
        int islands = Math.max(1, Integer.getInteger("candidate.cmaIslands", 4));
        int generations = Math.max(1, Integer.getInteger("candidate.cmaGenerations", 12));
        int population = Math.max(8, Integer.getInteger("candidate.cmaPopulation", 96));
        double sigma = Math.max(0.005, Math.min(1.0,
                Double.parseDouble(System.getProperty("candidate.cmaSigma", "0.20"))));
        ArrayList<ScoredVector> generated = new ArrayList<>(
                islands * generations * population);
        for (int island = 0; island < Math.min(islands, ranked.size()); island++) {
            Random random = new Random(seed + 0x9E3779B97F4A7C15L * (island + 1L));
            double[] base = ranked.get(island).vector();
            for (int generation = 0; generation < generations; generation++) {
                float[] features = new float[population * DIMENSIONS];
                double[][] vectors = new double[population][DIMENSIONS];
                for (int row = 0; row < population; row++) {
                    vectors[row] = base.clone();
                    for (int i = 0; i < DIMENSIONS; i++) {
                        vectors[row][i] += random.nextGaussian() * sigma;
                    }
                    CommonFunctions.normalizePolicyVector(vectors[row]);
                    for (int i = 0; i < DIMENSIONS; i++) {
                        features[row * DIMENSIONS + i] = (float) vectors[row][i];
                    }
                }
                float[] scores = new float[population];
                scorer.score(features, population, scores);
                for (int row = 0; row < population; row++) {
                    generated.add(new ScoredVector(vectors[row], scores[row]));
                }
            }
        }
        return List.copyOf(generated);
    }
}
