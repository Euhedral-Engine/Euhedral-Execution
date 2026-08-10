package io.euhedral_execution.core.config;

import io.euhedral_execution.core.control_plane.ControlPlaneCache;
import io.euhedral_execution.core.generics.CloneableObject;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;

/// ### Configuration for the [ControlPlaneCache][ControlPlaneCache]
///
/// @param cloneConfig            See [CloneConfig]
/// @param memoryBudget           Percentage of L2 and L1 cache that will be used to size the
/// queues. (0.0, 1.0]
/// @param partitions             Number of partitions in the cache.
/// @param maxPooledChunks        Number of chunks per partition to store after the unbounded queues
/// expand.
/// @param ringWalkResetThreshold Minimum number of frames needed to collect during a traversal
/// through the partitions to restart the cycle. Higher values cause the drain loop to break faster.
/// Lower values cause it to spin longer.
/// @param metricPrefix           Prefix string to prepend to exported metrics.
/// @param registry               Registry for reporting collected metrics.
@SuppressWarnings("unused")
public record CacheConfig(
        @Nullable CloneConfig cloneConfig,
        double memoryBudget,
        int partitions,
        int maxPooledChunks,
        int ringWalkResetThreshold,
        @Nullable String metricPrefix,
        @Nullable MeterRegistry registry)
        implements CloneableObject {

    public CacheConfig {
        if (!Double.isFinite(memoryBudget) || memoryBudget <= 0) {
            throw new IllegalArgumentException(
                    "memoryBudget must be finite and greater than 0. Provided: " + memoryBudget);
        }
        if (partitions <= 0) {
            throw new IllegalArgumentException("partitions must be greater than 0. Provided: " + partitions);
        }
    }

    public static CacheConfig ofDefaults() {
        return ofDefaults(null, null);
    }

    public static CacheConfig ofDefaults(String metricPrefix, MeterRegistry registry) {
        return new CacheConfig(null, 0.7, 8, 0, 4, metricPrefix, registry);
    }

    @Override
    public CacheConfig clone(CloneConfig cloneConfig) {
        return new CacheConfig(
                cloneConfig, memoryBudget, partitions, maxPooledChunks, ringWalkResetThreshold, metricPrefix, registry);
    }

    @Override
    public int getCore() {
        if (this.cloneConfig != null) {
            return this.cloneConfig.coreId();
        }
        return -1;
    }
}
