package io.euhedral_execution.training.optimization;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.optimization.CmaEsOptimizer.ScoredVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Bounded reservoir sampling across empirical classifier-score bands. */
public final class ScoreBandSampler {

    private static final int[] DEFAULT_WEIGHTS = {1, 1, 1, 1, 2, 2, 3, 5, 8, 16};

    private final double[] thresholds;
    private final int[] capacities;
    private final long[] seen;
    private final List<ScoredVector>[] reservoirs;
    private final Random[] random;
    private final Set<Long> acceptedHashes;
    private final long shuffleSeed;

    @SuppressWarnings("unchecked")
    public ScoreBandSampler(double[] thresholds, int[] capacities, long seed) {
        if (capacities.length != thresholds.length + 1) {
            throw new IllegalArgumentException("There must be one more band than thresholds");
        }
        this.thresholds = thresholds.clone();
        this.capacities = capacities.clone();
        this.seen = new long[capacities.length];
        this.reservoirs = new List[capacities.length];
        this.random = new Random[capacities.length];
        this.acceptedHashes = new HashSet<>();
        this.shuffleSeed = seed;
        for (int band = 0; band < capacities.length; band++) {
            this.reservoirs[band] = new ArrayList<>(capacities[band]);
            this.random[band] = new Random(seed ^ (0x9E3779B97F4A7C15L * (band + 1L)));
        }
    }

    public void accept(double[] vector, float score) {
        long hash = HasherApi.getHash(vector);
        if (!this.acceptedHashes.add(hash)) {
            return;
        }

        int band = band(score);
        long observed = ++this.seen[band];
        int capacity = this.capacities[band];
        if (capacity == 0) {
            return;
        }
        ScoredVector candidate = new ScoredVector(vector.clone(), score);
        List<ScoredVector> reservoir = this.reservoirs[band];
        if (reservoir.size() < capacity) {
            reservoir.add(candidate);
            return;
        }
        long replacement = this.random[band].nextLong(observed);
        if (replacement < capacity) {
            reservoir.set((int) replacement, candidate);
        }
    }

    public List<ScoredVector> finish() {
        List<ScoredVector> result = new ArrayList<>();
        for (List<ScoredVector> reservoir : this.reservoirs) {
            result.addAll(reservoir);
        }
        Collections.shuffle(result, new Random(this.shuffleSeed));
        return result;
    }

    public static int[] topHeavyCapacities(int total) {
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        int[] capacities = new int[DEFAULT_WEIGHTS.length];
        if (total == 0) {
            return capacities;
        }

        int assigned = 0;
        if (total >= capacities.length) {
            for (int band = 0; band < capacities.length; band++) {
                capacities[band] = 1;
                assigned++;
            }
        }
        int weightTotal = 0;
        for (int weight : DEFAULT_WEIGHTS) {
            weightTotal += weight;
        }
        int remaining = total - assigned;
        double[] fractions = new double[capacities.length];
        for (int band = 0; band < capacities.length; band++) {
            double exact = remaining * (DEFAULT_WEIGHTS[band] / (double) weightTotal);
            int count = (int) Math.floor(exact);
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
            fractions[best] = -1;
            assigned++;
        }
        return capacities;
    }

    private int band(float score) {
        int band = 0;
        while (band < this.thresholds.length && score > this.thresholds[band]) {
            band++;
        }
        return band;
    }
}
