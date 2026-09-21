package io.euhedral_execution.core.config;

import java.util.BitSet;
import java.util.Objects;

/// Configuration for a [CloneableObject][io.euhedral_execution.core.generics.CloneableObject]
///
/// This configuration is instantiated and populated dynamically. The
/// [ControlPlaneLattice][io.euhedral_execution.core.control_plane.ControlPlaneLattice] and its
/// shards will create these and instantiate objects with them.
///
/// @param shardName     Name of the shard managing the clone
/// @param coreId        Physical ID of the assigned core
/// @param effectiveCpus     The logical cpus available for use on the core
/// @param livenessRegistry  Shared worker liveness publication for this clone and its SMT buddy
public record CloneConfig(String shardName, int coreId, BitSet effectiveCpus, CloneLivenessRegistry livenessRegistry) {

    public CloneConfig(String shardName, int coreId, BitSet effectiveCpus) {
        this(shardName, coreId, effectiveCpus, new CloneLivenessRegistry(effectiveCpus));
    }

    public CloneConfig {
        Objects.requireNonNull(effectiveCpus);
        Objects.requireNonNull(livenessRegistry);
        if (!livenessRegistry.matches(effectiveCpus)) {
            throw new IllegalArgumentException("Liveness registry must exactly match effective CPUs");
        }
    }

    /// Liveness is runtime wiring, not logical clone identity; preserve the old record equality shape.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CloneConfig that)) {
            return false;
        }
        return this.coreId == that.coreId
                && Objects.equals(this.shardName, that.shardName)
                && Objects.equals(this.effectiveCpus, that.effectiveCpus);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.shardName, this.coreId, this.effectiveCpus);
    }

    public int[] getCpuSet() {
        int[] cpus = new int[effectiveCpus.cardinality()];
        int idx = 0;
        for (int c = effectiveCpus.nextSetBit(0); c >= 0; c = effectiveCpus.nextSetBit(c + 1)) {
            cpus[idx++] = c;
        }
        return cpus;
    }
}
