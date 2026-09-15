package io.euhedral_execution.benchmarks.cfd.benchmark;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.execution.*;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import io.euhedral_execution.benchmarks.cfd.solver.Simulation;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.hardware_utils.ThreadTools;

/// One fork owns its runtime. Driver first-touch/reset and continuous scheduler history are explicit policies.
final class BenchmarkRuntime implements AutoCloseable {
    private ControlPlaneLattice lattice;
    private ExecutionBackend backend;
    private Simulation simulation;
    private final boolean affinity;

    BenchmarkRuntime(CfdConfiguration config, BackendOptions options, WorkerBudget budget) {
        affinity = options.affinity();
        try {
            BenchmarkSuite.require(
                    config.memory().totalBytes()
                                    + options.storageBytes(
                                            config.config()
                                                    .grid()
                                                    .brickCount(config.config()
                                                            .execution()
                                                            .brick()),
                                            budget.workerCount())
                            <= config.memory().budgetBytes(),
                    "backend storage exceeds the configured memory budget");
            for (int cpu : budget.effectiveCpus()) {
                BenchmarkSuite.require(ThreadTools.BASE_MASK.get(cpu), "fork lost an assigned CPU");
            }
            if (affinity) {
                ThreadTools.setAffinity(budget.driverCpu());
            }
            lattice = budget.lattice(options.shutdownTimeoutMillis());
            var geometry = GeometryMask.resolve(config, lattice);
            if (options.backend().equals("fjp") || options.backend().equals("static")) {
                lattice.close();
                lattice = null;
            }
            backend = switch (options.backend()) {
                case "fjp" -> new ForkJoinBackend(budget.effectiveCpus(), affinity, options.shutdownTimeoutMillis());
                case "static" -> new StaticBackend(budget.effectiveCpus(), affinity, options.shutdownTimeoutMillis());
                default ->
                    new EuhedralBackend(
                            lattice,
                            options.backend().equals("euhedral"),
                            options.sourceCount(budget.workerCount()),
                            false,
                            options.shutdownTimeoutMillis());
            };
            simulation = new Simulation(config, geometry, backend);
            checkWorkers(budget.workerCount());
        } catch (RuntimeException | Error error) {
            try {
                close();
            } catch (RuntimeException suppressed) {
                error.addSuppressed(suppressed);
            }
            throw error;
        }
    }

    Long framesCreated() {
        return backend instanceof EuhedralBackend euhedral ? euhedral.framesCreated() : null;
    }

    Simulation simulation() {
        return simulation;
    }

    void checkWorkers(int expected) {
        if (lattice != null && lattice.getActiveWorkers() != expected) {
            throw new IllegalStateException("effective worker budget changed");
        }
    }

    @Override
    public void close() {
        try {
            if (backend != null) {
                backend.close();
            }
        } finally {
            try {
                if (lattice != null) {
                    lattice.close();
                }
            } finally {
                if (affinity) {
                    ThreadTools.releaseAffinity();
                }
            }
        }
    }
}
