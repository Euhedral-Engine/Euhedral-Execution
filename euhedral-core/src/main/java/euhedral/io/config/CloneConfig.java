package euhedral.io.config;

import java.util.BitSet;

public record CloneConfig(String shardName, int coreId, double quotaCpus,
                          BitSet effectiveCpus) {

    public int[] getCpuSet() {
        int[] cpus = new int[effectiveCpus.cardinality()];
        int idx = 0;
        for (int c = effectiveCpus.nextSetBit(0); c >= 0; c = effectiveCpus.nextSetBit(c + 1)) {
            cpus[idx++] = c;
        }
        return cpus;
    }
}
