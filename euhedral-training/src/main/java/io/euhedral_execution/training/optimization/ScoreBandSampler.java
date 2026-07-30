package io.euhedral_execution.training.optimization;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.optimization.data.PredictedCandidate;
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
    private final PriorityQueue<PredictedCandidate> overflow;
    private final int overflowCapacity;
    private final Set<PolicyId> acceptedIds = new HashSet<>();

    @SuppressWarnings("unchecked")
    public ScoreBandSampler(int capacity, int[] bandWeights, long bandSeed, int iteration,
            int overflowCapacity) {
        if (capacity < 0 || iteration < 0 || overflowCapacity < 0) {
            throw new IllegalArgumentException("Requested counts must not be negative");
        }
        if (bandWeights.length != 10) {
            throw new IllegalArgumentException("Phase 3 score bands require ten weights");
        }
        this.capacities = HamiltonAllocator.allocate(capacity, bandWeights.clone(),
                BAND_TIE_ORDER);
        this.seed = bandSeed;
        this.iteration = iteration;
        this.bands = new List[10];
        for (int i = 0; i < bands.length; i++) {
            bands[i] = new ArrayList<>();
        }
        this.overflowCapacity = overflowCapacity;
        this.overflow = new PriorityQueue<>(Comparator.comparing(
                PredictedCandidate::prediction,
                PredictedPolicyComparator.BEST_FIRST.reversed()));
    }

    public void accept(PredictedCandidate candidate) {
        if (!acceptedIds.add(candidate.policy().id())) {
            return;
        }
        int band = band(candidate.prediction().predictedWorstQuality());
        retainBand(band, new Entry(candidate,
                SchedulerSeeds.scoreBandSamplingKey(seed, iteration, band,
                        candidate.policy().id())));
        retainOverflow(candidate);
    }

    public List<PredictedCandidate> finish() {
        ArrayList<PredictedCandidate> result = new ArrayList<>();
        Set<PolicyId> emitted = new HashSet<>();
        for (int band = 9; band >= 0; band--) {
            bands[band].stream().sorted(ScoreBandSampler::compareEntry)
                    .forEach(entry -> {
                        if (emitted.add(entry.candidate().policy().id())) {
                            result.add(entry.candidate());
                        }
                    });
        }
        ArrayList<PredictedCandidate> backfill = new ArrayList<>(overflow);
        backfill.sort(Comparator.comparing(PredictedCandidate::prediction,
                PredictedPolicyComparator.BEST_FIRST));
        int requested = sum(capacities);
        for (PredictedCandidate candidate : backfill) {
            if (result.size() >= requested) {
                break;
            }
            if (emitted.add(candidate.policy().id())) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
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
        retained.sort(ScoreBandSampler::compareEntry);
        if (retained.size() > capacity) {
            retained.removeLast();
        }
    }

    private void retainOverflow(PredictedCandidate candidate) {
        if (overflowCapacity == 0) {
            return;
        }
        overflow.add(candidate);
        if (overflow.size() > overflowCapacity) {
            overflow.poll();
        }
    }

    private static int sum(int[] values) {
        int sum = 0;
        for (int value : values) {
            sum = Math.addExact(sum, value);
        }
        return sum;
    }

    private static int compareEntry(Entry left, Entry right) {
        int result = Long.compareUnsigned(left.samplingKey(), right.samplingKey());
        return result != 0 ? result
                : left.candidate().policy().id().compareTo(right.candidate().policy().id());
    }

    private record Entry(PredictedCandidate candidate, long samplingKey) {
    }
}
