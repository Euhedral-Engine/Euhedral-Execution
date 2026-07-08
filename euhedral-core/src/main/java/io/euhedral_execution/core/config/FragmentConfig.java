package io.euhedral_execution.core.config;

import io.euhedral_execution.core.control_plane.ControlPlaneFragment;
import io.euhedral_execution.core.generics.CloneableObject;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// ### Configuration for the [ControlPlaneFragment][ControlPlaneFragment]
///
/// @param cloneConfig  See [CloneConfig]
/// @param cacheConfig  See [CacheConfig]
/// @param maxBatchSize The maximum size of the batches the fragment can scale up to
/// @param metricPrefix Prefix string to prepend to exported metrics.
/// @param registry     Registry for reporting collected metrics.
@SuppressWarnings("unused")
public record FragmentConfig(@Nullable CloneConfig cloneConfig,
                             @NonNull CacheConfig cacheConfig,
                             long maxBatchSize,
                             @Nullable String metricPrefix,
                             @Nullable MeterRegistry registry)
        implements CloneableObject {

    public static FragmentConfig ofDefaults() {
        return ofDefaults(null, null);
    }

    public static FragmentConfig ofDefaults(String metricPrefix, MeterRegistry meterRegistry) {
        return new FragmentConfig(null, CacheConfig.ofDefaults(metricPrefix, meterRegistry), 4_096,
                metricPrefix, meterRegistry);
    }

    public FragmentConfig {
        Objects.requireNonNull(cacheConfig);
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException(
                    "maxBatchSize must be greater than 0. Provided: " + maxBatchSize);
        }
    }

    @Override
    public FragmentConfig clone(CloneConfig cloneConfig) {
        return new FragmentConfig(cloneConfig, this.cacheConfig.clone(cloneConfig),
                this.maxBatchSize, this.metricPrefix, this.registry);
    }

    @Override
    public int getCore() {
        if (this.cloneConfig != null) {
            return this.cloneConfig.coreId();
        }
        return -1;
    }
}
