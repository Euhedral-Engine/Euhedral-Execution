package io.euhedral_execution.core.config;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.control_plane.ControlPlaneShard;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.impl.BaseCloneableObject;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.BitSet;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// ### Configuration for the [ControlPlaneLattice][ControlPlaneLattice]
///
/// @param name            Top-level name used in logging and thread naming
/// @param allowedCpus     Which CPUs the lattice can bind to.
/// @param shutdownTimeout Amount of time given to shards and clones to gracefully shut down before
/// forcefully shutting them down.
/// @param baseShard       Base [ControlPlaneShard][ControlPlaneShard] that will be cloned across
/// sockets.
@SuppressWarnings("unused")
public record LatticeConfig(String name, @NonNull BitSet allowedCpus,
                            @NonNull Duration shutdownTimeout,
                            @NonNull ControlPlaneShard baseShard) {
    public static final String DEFAULT_NAME = "EuhedralLattice";
    public static final String DEFAULT_SHARD_NAME = "EuhedralShard";

    public static LatticeConfig ofDefaults() {
        return ofDefaults(DEFAULT_NAME, DEFAULT_SHARD_NAME);
    }

    /// @param name      Top-level name
    /// @param shardName Second-level name
    public static LatticeConfig ofDefaults(String name, String shardName) {
        return ofDefaults(name, shardName, new BaseCloneableObject());
    }

    /// @param name         Top-level name
    /// @param shardName    Second-level name
    /// @param metricPrefix Prefix prepended to collected metrics
    /// @param registry     Registry for collecting metrics
    public static LatticeConfig ofDefaults(String name, String shardName, String metricPrefix,
            MeterRegistry registry) {
        return ofDefaults(name, shardName, new BaseCloneableObject(metricPrefix, registry));
    }

    /// @param executor Executor to give to [BaseCloneableObject]
    public static LatticeConfig ofDefaults(@NonNull AbstractExecutor executor) {
        return ofDefaults(DEFAULT_NAME, DEFAULT_SHARD_NAME, executor);
    }

    /// @param name      Top-level name
    /// @param shardName Second-level name
    /// @param executor  Executor to give to [BaseCloneableObject]
    public static LatticeConfig ofDefaults(String name, String shardName,
            AbstractExecutor executor) {
        return ofDefaults(name, shardName, new BaseCloneableObject(executor));
    }

    /// @param name         Top-level name
    /// @param shardName    Second-level name
    /// @param metricPrefix Prefix prepended to collected metrics
    /// @param registry     Registry for collecting metrics
    /// @param executor     Executor to give to [BaseCloneableObject]
    public static LatticeConfig ofDefaults(String name, String shardName, String metricPrefix,
            MeterRegistry registry, AbstractExecutor executor) {
        return ofDefaults(name, shardName,
                new BaseCloneableObject(metricPrefix, registry, executor));
    }

    /// @param name            Top-level name
    /// @param shardName       Second-level name
    /// @param cloneableObject Object to be replicated and assigned a core. See
    /// [BaseCloneableObject]
    public static LatticeConfig ofDefaults(String name, String shardName,
            @NonNull CloneableObject cloneableObject) {
        return new LatticeConfig(name, SystemInfo.getCpuSet(), Duration.ofMinutes(1),
                ControlPlaneShard.createBaseShard(shardName, cloneableObject));
    }

    /// @param cloneableObject Object to be replicated and assigned a core. See
    /// [BaseCloneableObject]
    public static LatticeConfig ofDefaults(@NonNull CloneableObject cloneableObject) {
        return ofDefaults(DEFAULT_NAME, DEFAULT_SHARD_NAME, cloneableObject);
    }

    public LatticeConfig {
        Objects.requireNonNull(allowedCpus);
        Objects.requireNonNull(shutdownTimeout);
        Objects.requireNonNull(baseShard);
    }
}
