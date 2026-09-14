package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.execution.EuhedralBackend;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import java.util.function.LongSupplier;

/// Compatibility entry point for ordered execution on a caller-owned lattice.
public final class SerialSimulation extends Simulation {
    public SerialSimulation(CfdConfiguration configuration, ControlPlaneLattice lattice) {
        this(configuration, lattice, System::nanoTime);
    }

    SerialSimulation(CfdConfiguration configuration, ControlPlaneLattice lattice, LongSupplier clock) {
        super(
                configuration,
                GeometryMask.resolve(configuration, lattice),
                new EuhedralBackend(lattice, false, 1, false, 1_000),
                clock);
    }
}
