package io.euhedral_execution.benchmarks.cfd.support;

import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import java.util.BitSet;

/// Bounds worker/cache allocation to the topology each correctness test actually exercises.
public final class CfdTestRuntime {
    private CfdTestRuntime() {}

    public static ControlPlaneLattice singleWorker() {
        return workers(1);
    }

    public static ControlPlaneLattice upToTwoWorkers() {
        return workers(2);
    }

    private static ControlPlaneLattice workers(int limit) {
        BitSet allowed = (BitSet) SystemInfo.getCpuSet().clone();
        allowed.and(ThreadTools.BASE_MASK);
        int cpu = allowed.nextSetBit(0);
        if (cpu < 0) throw new IllegalStateException("CFD tests need an available CPU");
        BitSet worker = new BitSet();
        BitSet cores = new BitSet();
        for (int candidate = cpu;
                candidate >= 0 && cores.cardinality() < limit;
                candidate = allowed.nextSetBit(candidate + 1)) {
            int core = SystemInfo.getCpuInfo(candidate).core();
            /// Core 0 is reserved when other cores are selected; SMT siblings are not extra workers.
            if (limit > 1 && core == 0) continue;
            if (cores.get(core)) continue;
            cores.set(core);
            worker.set(candidate);
        }
        /// A machine exposing only core 0 still supports the serial fixtures.
        if (worker.isEmpty()) worker.set(cpu);
        var defaults = LatticeConfig.ofDefaults();
        return ControlPlaneLattice.getOrCreate(
                new LatticeConfig(defaults.name(), worker, defaults.shutdownTimeout(), defaults.baseShard()));
    }
}
