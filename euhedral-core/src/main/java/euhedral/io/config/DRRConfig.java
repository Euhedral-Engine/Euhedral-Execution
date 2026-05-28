package euhedral.io.config;

import euhedral.io.generics.CloneableObject;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;

public record DRRConfig(@Nullable CloneConfig cloneConfig, double L2MemoryBudget,
                        int partitionsPerCpu, int maxPooledChunks,
                        int ringWalkResetThreshold, double queueCapFactor, String metricPrefix,
                        @Nullable MeterRegistry registry) implements CloneableObject {

    public static DRRConfig defaultConfig() {
        return defaultConfig(null, null);
    }

    public static DRRConfig defaultConfig(String metricPrefix, MeterRegistry registry) {
        return new DRRConfig(null, 0.7, 4, 1, 4, 0.8, metricPrefix, registry);
    }

    @Override
    public DRRConfig clone(CloneConfig cloneConfig) {
        MeterRegistry meterRegistry = null;
        if (cloneConfig != null) {
            meterRegistry = cloneConfig.meterRegistry();
        }
        return new DRRConfig(cloneConfig, L2MemoryBudget, partitionsPerCpu, maxPooledChunks,
                ringWalkResetThreshold,
                queueCapFactor,
                metricPrefix,
                meterRegistry);
    }

    @Override
    public void close() throws Exception {

    }

}
