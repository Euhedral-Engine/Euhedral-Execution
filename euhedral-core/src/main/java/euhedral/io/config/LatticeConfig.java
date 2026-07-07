package euhedral.io.config;

import euhedral.hardware_utils.SystemInfo;
import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.control_plane.ControlPlaneShard;
import euhedral.io.generics.CloneableObject;
import euhedral.io.impl.BaseCloneableObject;
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
/// @param baseShard       Base [ControlPlaneShard][euhedral.io.control_plane.ControlPlaneShard]
/// that will be cloned across sockets.
@SuppressWarnings("unused")
public record LatticeConfig(String name, @NonNull BitSet allowedCpus,
                            @NonNull Duration shutdownTimeout,
                            @NonNull ControlPlaneShard baseShard) {

    public static LatticeConfig ofDefaults() {
        return ofDefaults("EuhedralLattice", "EuhedralShard");
    }

    /// @param name Top-level name
    /// @param shardName Second-level name
    public static LatticeConfig ofDefaults(String name, String shardName) {
        return ofDefaults(name, shardName, new BaseCloneableObject());
    }

    /// @param name Top-level name
    /// @param shardName Second-level name
    /// @param metricPrefix Prefix prepended to collected metrics
    /// @param meterRegistry Registry for collecting metrics
    public static LatticeConfig ofDefaults(String name, String shardName, String metricPrefix,
            MeterRegistry meterRegistry) {
        return ofDefaults(name, shardName, new BaseCloneableObject(metricPrefix, meterRegistry));
    }

    /// @param name Top-level name
    /// @param shardName Second-level name
    /// @param cloneableObject Object to be replicated and assigned a core
    public static LatticeConfig ofDefaults(String name, String shardName,
            @NonNull CloneableObject cloneableObject) {
        return new LatticeConfig(name, SystemInfo.getCpuSet(), Duration.ofMinutes(1),
                ControlPlaneShard.createBaseShard(shardName, cloneableObject));
    }

    /// @param cloneableObject Object to be replicated and assigned a core
    public static LatticeConfig ofDefaults(@NonNull CloneableObject cloneableObject) {
        return ofDefaults("EuhedralLattice", "EuhedralShard", cloneableObject);
    }

    public LatticeConfig {
        Objects.requireNonNull(allowedCpus);
        Objects.requireNonNull(shutdownTimeout);
        Objects.requireNonNull(baseShard);
    }
}
