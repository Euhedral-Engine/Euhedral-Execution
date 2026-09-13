package io.euhedral_execution.benchmarks.cfd.support;

import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import java.util.BitSet;

/// Exercises the single-worker topology even on development hosts with many physical cores.
public final class CfdTestRuntime {
    private CfdTestRuntime() {}

    public static ControlPlaneLattice singleWorker() {
        BitSet allowed = (BitSet) SystemInfo.getCpuSet().clone();
        allowed.and(ThreadTools.BASE_MASK);
        int cpu = allowed.nextSetBit(0);
        if (cpu < 0) throw new IllegalStateException("CFD tests need an available CPU");
        BitSet worker = new BitSet();
        worker.set(cpu);
        /// With only one selected core, topology reservation retains that core even if its ID is 0.
        var defaults = LatticeConfig.ofDefaults();
        return ControlPlaneLattice.getOrCreate(
                new LatticeConfig(defaults.name(), worker, defaults.shutdownTimeout(), defaults.baseShard()));
    }
}
