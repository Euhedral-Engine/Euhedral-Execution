package io.euhedral_execution.core.config;

import io.euhedral_execution.core.control_plane.FragmentObserver;
import io.euhedral_execution.core.generics.CloneableObject;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// ### Configuration for the [ControlPlaneFragment][io.euhedral_execution.core.control_plane.ControlPlaneFragment]
///
/// @param cloneConfig  See [CloneConfig]
/// @param cacheConfig  See [CacheConfig]
/// @param maxBatchSize The maximum size of the batches the fragment can scale up to
/// @param idlePolicy CACHE park duration and acquisition-contention half-life
/// @param metricPrefix Prefix string to prepend to exported metrics.
/// @param registry     Registry for reporting collected metrics.
@SuppressWarnings("unused")
public record FragmentConfig(
        @Nullable CloneConfig cloneConfig,
        @NonNull CacheConfig cacheConfig,
        @NonNull FragmentDecisionWeights decisionWeights,
        @Nullable FragmentObserver observer,
        long maxBatchSize,
        boolean smtEnabled,
        @NonNull IdlePolicy idlePolicy,
        boolean benchmarkMode,
        @Nullable String metricPrefix,
        @Nullable MeterRegistry registry)
        implements CloneableObject {

    public static final long DEFAULT_CONTENTION_HALF_LIFE_NANOS = IdlePolicy.DEFAULT_CONTENTION_HALF_LIFE_NANOS;

    public FragmentConfig {
        Objects.requireNonNull(cacheConfig);
        Objects.requireNonNull(decisionWeights);
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be greater than 0. Provided: " + maxBatchSize);
        }
        Objects.requireNonNull(idlePolicy);
        if (benchmarkMode && observer == null) {
            throw new IllegalArgumentException("FragmentObserver cannot be null in benchmark mode");
        }
    }

    /// Compatibility constructor retaining the default CACHE park duration.
    public FragmentConfig(
            @Nullable CloneConfig cloneConfig,
            @NonNull CacheConfig cacheConfig,
            @NonNull FragmentDecisionWeights decisionWeights,
            @Nullable FragmentObserver observer,
            long maxBatchSize,
            boolean smtEnabled,
            long contentionHalfLifeNanos,
            boolean benchmarkMode,
            @Nullable String metricPrefix,
            @Nullable MeterRegistry registry) {
        this(
                cloneConfig,
                cacheConfig,
                decisionWeights,
                observer,
                maxBatchSize,
                smtEnabled,
                new IdlePolicy(IdlePolicy.DEFAULT_IDLE_PARK_NS, contentionHalfLifeNanos),
                benchmarkMode,
                metricPrefix,
                registry);
    }

    public long contentionHalfLifeNanos() {
        return idlePolicy.contentionHalfLifeNanos();
    }

    public static FragmentConfig ofDefaults() {
        return ofDefaults(null, null);
    }

    public static FragmentConfig ofDefaults(String metricPrefix, MeterRegistry meterRegistry) {
        return new FragmentConfig(
                null,
                CacheConfig.ofDefaults(metricPrefix, meterRegistry),
                FragmentDecisionWeights.DEFAULT,
                null,
                4_096,
                true,
                IdlePolicy.DEFAULT,
                false,
                metricPrefix,
                meterRegistry);
    }

    public static FragmentConfig ofBenchmark(
            @NonNull FragmentObserver observer, @NonNull FragmentDecisionWeights decisionWeights) {
        return ofBenchmark(observer, decisionWeights, IdlePolicy.DEFAULT);
    }

    public static FragmentConfig ofBenchmark(
            @NonNull FragmentObserver observer,
            @NonNull FragmentDecisionWeights decisionWeights,
            @NonNull IdlePolicy idlePolicy) {
        Objects.requireNonNull(observer);
        return new FragmentConfig(
                null, CacheConfig.ofDefaults(), decisionWeights, observer, 4_096, true, idlePolicy, true, null, null);
    }

    @Override
    public FragmentConfig clone(CloneConfig cloneConfig) {
        return new FragmentConfig(
                cloneConfig,
                this.cacheConfig.clone(cloneConfig),
                this.decisionWeights,
                this.observer,
                this.maxBatchSize,
                this.smtEnabled,
                this.idlePolicy,
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
