package euhedral.io.config;

import euhedral.io.generics.CloneableObject;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;

public record CacheConfig(@Nullable CloneConfig cloneConfig, double L2MemoryBudget,
                          int partitionsPerCpu, int maxPooledChunks,
                          int ringWalkResetThreshold, String metricPrefix,
                          @Nullable MeterRegistry registry) implements CloneableObject {

    public static CacheConfig defaultConfig() {
        return defaultConfig(null, null);
    }

    public static CacheConfig defaultConfig(String metricPrefix, MeterRegistry registry) {
        return new CacheConfig(null, 0.7, 8, 1, 4, metricPrefix, registry);
    }

    @Override
    public CacheConfig clone(CloneConfig cloneConfig) {
        return new CacheConfig(cloneConfig, L2MemoryBudget, partitionsPerCpu, maxPooledChunks,
                ringWalkResetThreshold,
                metricPrefix, registry);
    }

    @Override
    public void close() throws Exception {

    }

}
