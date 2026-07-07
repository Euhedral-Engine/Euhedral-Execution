package euhedral.io.config;

import euhedral.io.generics.CloneableObject;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("unused")
public record CacheConfig(@Nullable CloneConfig cloneConfig,
                          double memoryBudget,
                          int partitions,
                          int maxPooledChunks,
                          int ringWalkResetThreshold,
                          String metricPrefix,
                          @Nullable MeterRegistry registry
) implements CloneableObject {

    public static CacheConfig ofDefault() {
        return ofDefault(null, null);
    }

    public static CacheConfig ofDefault(String metricPrefix, MeterRegistry registry) {
        return new CacheConfig(null, 0.7, 8, 1, 4, metricPrefix, registry);
    }

    @Override
    public CacheConfig clone(CloneConfig cloneConfig) {
        return new CacheConfig(
                cloneConfig,
                memoryBudget,
                partitions,
                maxPooledChunks,
                ringWalkResetThreshold,
                metricPrefix,
                registry
        );
    }

    @Override
    public int getCore() {
        if (this.cloneConfig != null) {
            return this.cloneConfig.coreId();
        }
        return -1;
    }
}
