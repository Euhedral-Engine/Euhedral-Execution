package io.euhedral_execution.training.learning;

import io.euhedral_execution.hashing.HasherApi;
import java.util.Random;

final class DeterministicBatchSampler {
    private final int rows;
    private final long memberSeed;

    DeterministicBatchSampler(int rows, long memberSeed) {
        if (rows <= 0) throw new IllegalArgumentException("Rows must be positive");
        this.rows = rows;
        this.memberSeed = memberSeed;
    }

    int[] order(int epoch) {
        if (epoch < 0) throw new IllegalArgumentException("Epoch must not be negative");
        int[] order = new int[rows];
        for (int index = 0; index < rows; index++) order[index] = index;
        long epochSeed = HasherApi.getHash("scenario-ordinal-v1/epoch/" + epoch, memberSeed);
        Random random = new Random(epochSeed);
        for (int index = rows - 1; index > 0; index--) {
            int replacement = random.nextInt(index + 1);
            int value = order[index];
            order[index] = order[replacement];
            order[replacement] = value;
        }
        return order;
    }
}
