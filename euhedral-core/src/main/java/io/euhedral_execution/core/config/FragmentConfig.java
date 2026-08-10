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
public record FragmentConfig(
        @Nullable CloneConfig cloneConfig,
        @NonNull CacheConfig cacheConfig,
        @NonNull FragmentActionPicker actionPicker,
        long maxBatchSize,
        boolean benchmarkMode,
        @Nullable String metricPrefix,
        @Nullable MeterRegistry registry)
        implements CloneableObject {

    public FragmentConfig {
        Objects.requireNonNull(cacheConfig);
        Objects.requireNonNull(actionPicker);
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be greater than 0. Provided: " + maxBatchSize);
        }
    }

    public static FragmentConfig ofDefaults() {
        return ofDefaults(null, null);
    }

    public static FragmentConfig ofDefaults(String metricPrefix, MeterRegistry meterRegistry) {
        return new FragmentConfig(
                null,
                CacheConfig.ofDefaults(metricPrefix, meterRegistry),
                FragmentActionPicker.ofDefaults(),
                4_096,
                false,
                metricPrefix,
                meterRegistry);
    }

    public static FragmentConfig ofBenchmark(FragmentActionPicker actionPicker) {
        return new FragmentConfig(null, CacheConfig.ofDefaults(), actionPicker, 4_096, true, null, null);
    }

    @Override
    public FragmentConfig clone(CloneConfig cloneConfig) {
        return new FragmentConfig(
                cloneConfig,
                this.cacheConfig.clone(cloneConfig),
                this.actionPicker,
                this.maxBatchSize,
                this.benchmarkMode,
                this.metricPrefix,
                this.registry);
    }

    @Override
    public int getCore() {
        if (this.cloneConfig != null) {
            return this.cloneConfig.coreId();
        }
        return -1;
    }
}
