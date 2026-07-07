package euhedral.io.config;

import euhedral.io.generics.CloneableObject;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("unused")
public record FragmentConfig(@Nullable CloneConfig cloneConfig,
                             @NonNull CacheConfig cacheConfig,
                             long maxBatchSize,
                             @Nullable String metricPrefix,
                             @Nullable MeterRegistry meterRegistry)
        implements CloneableObject {

    public static FragmentConfig ofDefault() {
        return ofDefault(null, null);
    }

    public static FragmentConfig ofDefault(String metricPrefix, MeterRegistry meterRegistry) {
        return new FragmentConfig(null, CacheConfig.ofDefault(metricPrefix, meterRegistry), 4_096, metricPrefix, meterRegistry);
    }

    @Override
    public FragmentConfig clone(CloneConfig cloneConfig) {
        return new FragmentConfig(cloneConfig, this.cacheConfig.clone(cloneConfig), this.maxBatchSize, this.metricPrefix, this.meterRegistry);
    }

    @Override
    public int getCore() {
        if(this.cloneConfig != null) {
            return this.cloneConfig.coreId();
        }
        return -1;
    }
}
