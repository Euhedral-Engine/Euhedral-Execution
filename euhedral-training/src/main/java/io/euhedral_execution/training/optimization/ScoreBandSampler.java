package io.euhedral_execution.training.optimization;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.optimization.CmaEsOptimizer.ScoredVector;
import io.euhedral_execution.training.scheduling.HamiltonAllocator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

public final class ScoreBandSampler {
    private static final int[] DEFAULT_WEIGHTS = {1, 1, 1, 1, 2, 2, 3, 5, 8, 16};
    private static final int[] BAND_TIE_ORDER = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0};

    private final int[] capacities;
    private final long seed;
    private final int iteration;
    private final List<Entry>[] bands;
    private final PriorityQueue<PredictedPolicySummary> overflow;
    private final int overflowCapacity;
    private final Set<PolicyId> acceptedIds = new HashSet<>();

    @SuppressWarnings("unchecked")
    public ScoreBandSampler(int requested, int auditQuota, long seed) {
        this(topHeavyCapacities(requested), seed, 0,
                overflowCapacity(requested, auditQuota));
    }

    @SuppressWarnings("unchecked")
    public ScoreBandSampler(int capacity, int[] bandWeights, long bandSeed, int iteration,
            int overflowCapacity) {
        if (capacity < 0 || iteration < 0 || overflowCapacity < 0) {
            throw new IllegalArgumentException("Requested counts must not be negative");
        }
        if (bandWeights.length != 10) {
            throw new IllegalArgumentException("Phase 3 score bands require ten capacities");
        }
        this.capacities = HamiltonAllocator.allocate(capacity, bandWeights, BAND_TIE_ORDER);
        this.seed = bandSeed;
        this.iteration = iteration;
        this.bands = new List[10];
        for (int i = 0; i < bands.length; i++) {
            bands[i] = new ArrayList<>();
        }
        this.overflowCapacity = overflowCapacity;
        this.overflow = new PriorityQueue<>(PredictedPolicyComparator.BEST_FIRST.reversed());
    }

    public ScoreBandSampler(double[] ignoredThresholds, int[] capacities, long seed) {
        this(capacities, seed, 0, sum(capacities));
        if (capacities.length != 10) {
            throw new IllegalArgumentException("Phase 3 score bands require ten capacities");
        }
        System.arraycopy(capacities, 0, this.capacities, 0, capacities.length);
    }

    @SuppressWarnings("unchecked")
    private ScoreBandSampler(int[] capacities, long seed, int iteration, int overflowCapacity) {
        if (iteration < 0 || overflowCapacity < 0) {
            throw new IllegalArgumentException("Requested counts must not be negative");
        }
        if (capacities.length != 10) {
            throw new IllegalArgumentException("Phase 3 score bands require ten capacities");
        }
        this.capacities = capacities.clone();
        this.seed = seed;
        this.iteration = iteration;
        this.bands = new List[10];
        for (int i = 0; i < bands.length; i++) {
            bands[i] = new ArrayList<>();
        }
        this.overflowCapacity = overflowCapacity;
        this.overflow = new PriorityQueue<>(PredictedPolicyComparator.BEST_FIRST.reversed());
    }

    private static int overflowCapacity(int requested, int auditQuota) {
        if (requested < 0 || auditQuota < 0) {
            throw new IllegalArgumentException("Requested counts must not be negative");
        }
        return Math.addExact(requested, auditQuota);
    }

    public void accept(PredictedPolicySummary summary) {
        if (!acceptedIds.add(summary.policy().id())) {
            return;
        }
        int band = band(summary.predictedWorstQuality());
        retainBand(band, new Entry(summary, samplingKey(summary.policy().id(), band)));
        retainOverflow(summary);
    }

    /** Compatibility adapter for pooled-v0 tests and transitional callers. */
    public void accept(double[] vector, float score) {
        double quality = Math.max(0.0, Math.min(1.0, score / 10.0));
        PolicyVector policy = PolicyVector.of(vector);
        PredictedPolicySummary summary = new PredictedPolicySummary(policy, List.of(
                new io.euhedral_execution.training.learning.ScenarioPrediction(
                        io.euhedral_execution.training.data.SourceScenario.of("legacy", 1, 1),
                        quality, 0.0, quality, quality, 0.0, quality, 0.0, 0.0)),
                quality, quality, quality, 0.0, 0.0, 0.0, 0.0, 0.0, quality);
        accept(summary);
    }

    public List<PredictedPolicySummary> finishPredicted() {
        List<PredictedPolicySummary> result = new ArrayList<>();
        Set<PolicyId> emitted = new HashSet<>();
        for (int band = 9; band >= 0; band--) {
            bands[band].sort(Comparator.comparingLong(Entry::samplingKey)
                    .thenComparing(entry -> entry.summary().policy().id()));
            for (Entry entry : bands[band]) {
                if (emitted.add(entry.summary().policy().id())) {
                    result.add(entry.summary());
                }
            }
        }
        List<PredictedPolicySummary> backfill = new ArrayList<>(overflow);
        backfill.sort(PredictedPolicyComparator.BEST_FIRST);
        int requested = sum(capacities);
        for (PredictedPolicySummary summary : backfill) {
            if (result.size() >= requested) {
                break;
            }
            if (emitted.add(summary.policy().id())) {
                result.add(summary);
            }
        }
        return List.copyOf(result);
    }

    /** Compatibility adapter for pooled-v0 tests and transitional callers. */
    public List<ScoredVector> finish() {
        List<ScoredVector> result = new ArrayList<>();
        for (PredictedPolicySummary summary : finishPredicted()) {
            result.add(new ScoredVector(summary.policy().copyWeights(),
                    (float) (summary.predictedWorstQuality() * 10.0)));
        }
        return result;
    }

    public static int[] topHeavyCapacities(int total) {
        return HamiltonAllocator.allocate(total, DEFAULT_WEIGHTS, BAND_TIE_ORDER);
    }

    public static int band(double predictedWorstQuality) {
        if (!Double.isFinite(predictedWorstQuality) || predictedWorstQuality < 0.0
                || predictedWorstQuality > 1.0) {
            throw new IllegalArgumentException("Quality must be in [0, 1]");
        }
        return Math.min(9, (int) StrictMath.floor(predictedWorstQuality * 10.0));
    }

    private void retainBand(int band, Entry entry) {
        int capacity = capacities[band];
        if (capacity == 0) {
            return;
        }
        List<Entry> retained = bands[band];
        retained.add(entry);
        retained.sort(Comparator.comparingLong(Entry::samplingKey)
                .thenComparing(e -> e.summary().policy().id()));
        if (retained.size() > capacity) {
            retained.remove(retained.size() - 1);
        }
    }

    private void retainOverflow(PredictedPolicySummary summary) {
        if (overflowCapacity == 0) {
            return;
        }
        overflow.add(summary);
        if (overflow.size() > overflowCapacity) {
            overflow.poll();
        }
    }

    private long samplingKey(PolicyId policyId, int band) {
        String material = "phase3-score-band-v1\n"
                + "iteration=" + iteration + "\n"
                + "band=" + band + "\n"
                + "policy=" + policyId.canonical() + "\n";
        return HasherApi.getHash(material, seed);
    }

    private static int sum(int[] values) {
        int sum = 0;
        for (int value : values) {
            sum = Math.addExact(sum, value);
        }
        return sum;
    }

    private record Entry(PredictedPolicySummary summary, long samplingKey) {
    }
}
