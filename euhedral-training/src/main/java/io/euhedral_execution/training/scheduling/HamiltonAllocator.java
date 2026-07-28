package io.euhedral_execution.training.scheduling;

import java.util.Arrays;

public final class HamiltonAllocator {
    public static int[] allocate(int total, int[] weights, int[] tieOrder) {
        if (total < 0 || weights.length == 0 || tieOrder.length != weights.length) {
            throw new IllegalArgumentException("Invalid Hamilton allocation inputs");
        }
        long weightSum = 0;
        for (int weight : weights) {
            if (weight < 0) {
                throw new IllegalArgumentException("Weights must not be negative");
            }
            weightSum += weight;
            if (weightSum > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Weight sum overflows int");
            }
        }
        if (weightSum == 0) {
            throw new IllegalArgumentException("At least one weight must be positive");
        }
        boolean[] seen = new boolean[weights.length];
        for (int index : tieOrder) {
            if (index < 0 || index >= weights.length || seen[index]) {
                throw new IllegalArgumentException("Tie order must be a permutation");
            }
            seen[index] = true;
        }
        int[] result = new int[weights.length];
        long[] remainder = new long[weights.length];
        int assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            long product = Math.multiplyExact((long) total, weights[i]);
            result[i] = (int) (product / weightSum);
            remainder[i] = product % weightSum;
            assigned += result[i];
        }
        while (assigned < total) {
            int best = tieOrder[0];
            for (int index : tieOrder) {
                if (remainder[index] > remainder[best]) {
                    best = index;
                }
            }
            result[best]++;
            remainder[best] = -1;
            assigned++;
        }
        if (Arrays.stream(result).sum() != total) {
            throw new IllegalStateException("Hamilton allocation did not sum to total");
        }
        return result;
    }

    private HamiltonAllocator() {
    }
}
