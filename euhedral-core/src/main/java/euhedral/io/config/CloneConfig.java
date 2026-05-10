package euhedral.io.config;

import euhedral.io.utils.FluxResourceMonitor;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.BitSet;

public record CloneConfig(String shardName, int coreId, double quotaCpus,
                          BitSet effectiveCpus, FluxResourceMonitor resourceMonitor,
                          MeterRegistry meterRegistry, String metricPrefix) {
    public int[] getCpuSet() {
        int[] cpus = new int[effectiveCpus.cardinality()];
        int idx = 0;
        for (int c = effectiveCpus.nextSetBit(0); c >= 0; c = effectiveCpus.nextSetBit(c + 1)) {
            cpus[idx++] = c;
        }
        return cpus;
    }
}
