package io.euhedral_execution.core.config;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.generics.CloneableObject;
import java.util.BitSet;
import java.util.Objects;

/// Configuration for a [CloneableObject][CloneableObject]
///
/// This configuration is instantiated and populated dynamically. The
/// [ControlPlaneLattice][ControlPlaneLattice] and its shards will create
/// these and instantiate objects with them.
///
/// @param shardName Name of the shard managing the clone
/// @param coreId Physical ID of the assigned core
/// @param effectiveCpus The logical cpus available for use on the core
public record CloneConfig(String shardName, int coreId, BitSet effectiveCpus) {

    public CloneConfig {
        Objects.requireNonNull(effectiveCpus);
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
