package io.euhedral_execution.training.optimization;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.merge.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.merge.RobustPolicyComparator;
import io.euhedral_execution.training.utils.CommonFunctions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Deterministic Phase 3 CMA-style proposal generator over normalized 28-weight policies. */
public final class CmaEsOptimizer {

    public static final int DIMENSIONS = 28;

    @FunctionalInterface
    public interface BatchScorer {
        void score(float[] features, int rows, float[] scores);
    }

    /** ROBUST_OPTIMIZER_POOLED_V0_REMOVAL compatibility for legacy.PooledSequenceFinder. */
    public record MeasuredPolicy(double[] vector, double[] quantiles) {
        public MeasuredPolicy {
            vector = Arrays.copyOf(vector, vector.length);
            quantiles = Arrays.copyOf(quantiles, quantiles.length);
        }
    }

    /** ROBUST_OPTIMIZER_POOLED_V0_REMOVAL compatibility for legacy.PooledSequenceFinder. */
    public record ScoredVector(double[] vector, float score) {
        public ScoredVector {
            vector = Arrays.copyOf(vector, vector.length);
        }
    }

    public List<PredictedCandidate> optimize(List<RobustPolicySummary> measuredEligiblePolicies,
            Set<PolicyId> fixedAnchorIds, PolicyCurvePredictor predictor, CmaEsConfig config,
            long islandSeed) {
        if (!config.enabled()) {
            return List.of();
        }
        List<RobustPolicySummary> seeds = measuredEligiblePolicies.stream()
                .filter(RobustPolicySummary::eligible)
                .filter(summary -> !fixedAnchorIds.contains(summary.policy().id()))
                .sorted(RobustPolicyComparator.BEST_FIRST)
                .toList();
        if (seeds.size() < config.minimumSeedPolicies()) {
            return List.of();
        }
        int islandCount = Math.min(config.islands(), seeds.size());
        List<PolicyVector> islandSeeds = islandSeeds(seeds, islandCount);
        ArrayList<PolicyVector> proposals = new ArrayList<>(
                islandCount * config.generations() * config.populationSize());
        Set<PolicyId> emitted = new HashSet<>();
        for (int island = 0; island < islandSeeds.size(); island++) {
            Random random = new Random(islandSeed + 0x9E3779B97F4A7C15L * (island + 1L));
            double[] mean = islandSeeds.get(island).copyWeights();
            for (int generation = 0; generation < config.generations(); generation++) {
                ArrayList<PolicyVector> population = new ArrayList<>(config.populationSize());
                for (int row = 0; row < config.populationSize(); row++) {
                    double[] vector = mean.clone();
                    for (int i = 0; i < vector.length; i++) {
                        vector[i] += random.nextGaussian() * config.initialSigma();
                    }
                    CommonFunctions.normalizePolicyVector(vector);
                    PolicyVector policy = PolicyVector.of(vector);
                    if (emitted.add(policy.id())) {
                        population.add(policy);
                    }
                }
                List<PredictedPolicySummary> predictions = predictor.predict(population);
                predictions.stream().sorted(PredictedPolicyComparator.BEST_FIRST)
                        .map(PredictedPolicySummary::policy).forEach(proposals::add);
                if (!predictions.isEmpty()) {
                    mean = predictions.stream().min(PredictedPolicyComparator.BEST_FIRST)
                            .orElseThrow().policy().copyWeights();
                }
            }
        }
        return predictor.predict(proposals).stream()
                .sorted(PredictedPolicyComparator.BEST_FIRST)
                .map(summary -> new PredictedCandidate(summary.policy(), summary,
                        CandidateOrigin.CMA_ES))
                .toList();
    }

    /** ROBUST_OPTIMIZER_POOLED_V0_REMOVAL compatibility for legacy.PooledSequenceFinder. */
    public List<ScoredVector> optimize(List<MeasuredPolicy> measured, BatchScorer scorer,
            long seed) {
        if (!Boolean.parseBoolean(System.getProperty("candidate.cmaEnabled", "true"))
                || measured.size() < 10) {
            return List.of();
        }
        List<MeasuredPolicy> ranked = new ArrayList<>(measured);
        ranked.sort((left, right) -> compareQuantiles(right.quantiles(), left.quantiles()));
        int islands = Math.max(1, Integer.getInteger("candidate.cmaIslands", 4));
        int generations = Math.max(1, Integer.getInteger("candidate.cmaGenerations", 12));
        int population = Math.max(8, Integer.getInteger("candidate.cmaPopulation", 96));
        double sigma = Math.max(0.005, Math.min(1.0,
                Double.parseDouble(System.getProperty("candidate.cmaSigma", "0.20"))));
        ArrayList<ScoredVector> generated = new ArrayList<>(islands * generations * population);
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
                        features[row * DIMENSIONS + i] = (float) vectors[row][i];
                    }
                    CommonFunctions.normalizePolicyVector(vectors[row]);
                }
                float[] scores = new float[population];
                scorer.score(features, population, scores);
                for (int row = 0; row < population; row++) {
                    generated.add(new ScoredVector(vectors[row], scores[row]));
                }
            }
        }
        return generated;
    }

    private static List<PolicyVector> islandSeeds(List<RobustPolicySummary> ranked, int requested) {
        ArrayList<PolicyVector> selected = new ArrayList<>(requested);
        selected.add(ranked.getFirst().policy());
        int poolSize = Math.min(ranked.size(), Math.max(requested * 32, 64));
        while (selected.size() < requested && selected.size() < poolSize) {
            RobustPolicySummary best = null;
            double bestDistance = -1.0;
            for (int i = 1; i < poolSize; i++) {
                PolicyVector candidate = ranked.get(i).policy();
                if (selected.stream().anyMatch(candidate::bitwiseEquals)) {
                    continue;
                }
                double distance = selected.stream()
                        .mapToDouble(chosen -> squaredDistance(candidate, chosen))
                        .min().orElse(0.0);
                if (distance > bestDistance
                        || Double.compare(distance, bestDistance) == 0
                        && RobustPolicyComparator.BEST_FIRST.compare(ranked.get(i), best) < 0) {
                    bestDistance = distance;
                    best = ranked.get(i);
                }
            }
            if (best == null) {
                break;
            }
            selected.add(best.policy());
        }
        return List.copyOf(selected);
    }

    private static double squaredDistance(PolicyVector left, PolicyVector right) {
        double distance = 0.0;
        for (int i = 0; i < DIMENSIONS; i++) {
            double delta = left.weight(i) - right.weight(i);
            distance += delta * delta;
        }
        return distance;
    }

    private static int compareQuantiles(double[] left, double[] right) {
        for (int i = Math.min(left.length, right.length) - 1; i >= 0; i--) {
            int result = Double.compare(left[i], right[i]);
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
