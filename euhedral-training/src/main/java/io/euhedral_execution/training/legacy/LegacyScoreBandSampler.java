package io.euhedral_execution.training.legacy;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.legacy.LegacyCmaEsOptimizer.ScoredVector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

// ROBUST_OPTIMIZER_POOLED_V0_REMOVAL
final class LegacyScoreBandSampler {
    private static final int[] DEFAULT_WEIGHTS = {1, 1, 1, 1, 2, 2, 3, 5, 8, 16};

    private final double[] thresholds;
    private final int[] capacities;
    private final long[] seen;
    private final List<ScoredVector>[] reservoirs;
    private final Random[] random;
    private final Set<Long> acceptedHashes = new HashSet<>();
    private final long shuffleSeed;

    @SuppressWarnings("unchecked")
    LegacyScoreBandSampler(double[] thresholds, int[] capacities, long seed) {
        if (capacities.length != thresholds.length + 1) {
            throw new IllegalArgumentException("There must be one more band than thresholds");
        }
        this.thresholds = thresholds.clone();
        this.capacities = capacities.clone();
        this.seen = new long[capacities.length];
        this.reservoirs = new List[capacities.length];
        this.random = new Random[capacities.length];
        this.shuffleSeed = seed;
        for (int band = 0; band < capacities.length; band++) {
            reservoirs[band] = new ArrayList<>(capacities[band]);
            random[band] = new Random(seed ^ (0x9E3779B97F4A7C15L * (band + 1L)));
        }
    }

    void accept(double[] vector, float score) {
        if (!acceptedHashes.add(HasherApi.getHash(vector))) {
            return;
        }
        int band = band(score);
        long observed = ++seen[band];
        int capacity = capacities[band];
        if (capacity == 0) {
            return;
        }
        ScoredVector candidate = new ScoredVector(vector, score);
        List<ScoredVector> reservoir = reservoirs[band];
        if (reservoir.size() < capacity) {
            reservoir.add(candidate);
            return;
        }
        long replacement = random[band].nextLong(observed);
        if (replacement < capacity) {
            reservoir.set((int) replacement, candidate);
        }
    }

    List<ScoredVector> finish() {
        ArrayList<ScoredVector> result = new ArrayList<>();
        for (List<ScoredVector> reservoir : reservoirs) {
            result.addAll(reservoir);
        }
        Collections.shuffle(result, new Random(shuffleSeed));
        return List.copyOf(result);
    }

    static int[] topHeavyCapacities(int total) {
        if (total < 0) {
            throw new IllegalArgumentException("Total must not be negative");
        }
        int[] capacities = new int[DEFAULT_WEIGHTS.length];
        int assigned = 0;
        if (total >= capacities.length) {
            Arrays.fill(capacities, 1);
            assigned = capacities.length;
        }
        int weightTotal = 0;
        for (int weight : DEFAULT_WEIGHTS) {
            weightTotal += weight;
        }
        int remaining = total - assigned;
        double[] fractions = new double[capacities.length];
        for (int band = 0; band < capacities.length; band++) {
            double exact = remaining * (DEFAULT_WEIGHTS[band] / (double) weightTotal);
            int count = (int) StrictMath.floor(exact);
            capacities[band] += count;
            fractions[band] = exact - count;
            assigned += count;
        }
        while (assigned < total) {
            int best = 0;
            for (int band = 1; band < fractions.length; band++) {
                if (fractions[band] > fractions[best]) {
                    best = band;
                }
            }
            capacities[best]++;
            fractions[best] = -1.0;
            assigned++;
        }
        return capacities;
    }

    private int band(float score) {
        int band = 0;
        while (band < thresholds.length && score > thresholds[band]) {
            band++;
        }
        return band;
    }
}
