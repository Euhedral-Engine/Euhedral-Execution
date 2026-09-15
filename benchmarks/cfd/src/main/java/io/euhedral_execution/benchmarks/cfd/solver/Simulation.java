package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.execution.ExecutionBackend;
import io.euhedral_execution.benchmarks.cfd.execution.RangePlan;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import java.util.Objects;
import java.util.function.LongSupplier;

/// Owns setup and generation boundaries; the reusable range frame owns parallelizable work.
public class Simulation implements AutoCloseable {
    private final CfdConfiguration configuration;
    private final SimulationState state;
    private final ExecutionBackend backend;
    private FlowDiagnostics pendingFlow;
    private final LongSupplier clock;
    private final double[] diagnosticScratch = new double[5];
    private boolean failed;
    private boolean closed;

    public Simulation(CfdConfiguration configuration, GeometryMask geometry, ExecutionBackend backend) {
        this(configuration, geometry, backend, System::nanoTime);
    }

    protected Simulation(
            CfdConfiguration configuration, GeometryMask geometry, ExecutionBackend backend, LongSupplier clock) {
        this.configuration = Objects.requireNonNull(configuration);
        this.clock = Objects.requireNonNull(clock);
        this.backend = Objects.requireNonNull(backend);
        state = new SimulationState(
                new PopulationGrid(configuration),
                configuration.physics(),
                geometry,
                new FlowDiagnostics(
                        geometry, configuration.config().physics().forceReference(), configuration.physics()));
        pendingFlow = new FlowDiagnostics(
                geometry, configuration.config().physics().forceReference(), configuration.physics());
        initialize();
        backend.prepare(new RangePlan(
                state.geometry(),
                configuration.config().execution().brick(),
                configuration.config().execution().bricksPerFrame()));
    }

    public SimulationState state() {
        return state;
    }

    /// Driver-only invocation reset after successful completion. Buffers, frames and workers are retained.
    public void reset() {
        if (closed || failed) {
            throw new IllegalStateException("cannot reset a closed or failed simulation");
        }
        state.reset();
        pendingFlow.reset();
        initialize();
    }

    public SimulationState run() {
        while (state.completedSteps() < configuration.steps()) {
            step();
        }
        return state;
    }

    public void step() {
        if (closed) {
            throw new IllegalStateException("simulation is closed");
        }
        if (failed) {
            throw new IllegalStateException("simulation has failed; last completed state remains available");
        }
        if (state.completedSteps() >= configuration.steps()) {
            throw new IllegalStateException("configured duration completed");
        }
        var context = new StepContext(
                state.shape(),
                state.current(),
                state.next(),
                1 / configuration.physics().tau(),
                Math.addExact(state.completedSteps(), 1),
                clock.getAsLong(),
                configuration.config().execution().stepDeadlineMillis() * 1_000_000,
                clock,
                state.geometry(),
                configuration.physics().acceleration(),
                configuration.physics().densityReference(),
                configuration.config().physics().guards());
        try {
            backend.execute(context, pendingFlow);
            long interval = configuration.config().execution().diagnosticsEverySteps();
            var diagnostics =
                    context.step() == configuration.steps() || (interval > 0 && context.step() % interval == 0)
                            ? FieldExtractor.summarize(
                                    context.next(),
                                    state.shape(),
                                    configuration.physics().densityReference(),
                                    context.step(),
                                    context,
                                    state.geometry(),
                                    configuration.physics().acceleration(),
                                    configuration.config().physics().guards(),
                                    diagnosticScratch)
                            : null;
            context.checkProgress(0, 0, 0);
            var previousFlow = state.flowDiagnostics();
            state.complete(context.step(), diagnostics, pendingFlow);
            pendingFlow = previousFlow;
        } catch (RuntimeException | Error e) {
            failed = true;
            backend.cancel();
            throw e;
        }
    }

    /// Backend shutdown waits for workers before any frame or buffer can be released.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        backend.close();
    }

    private void initialize() {
        var physics = configuration.physics();
        var shape = state.shape();
        var shear = physics.shear();
        double[][] populations = state.current();
        for (int z = 0; z < shape.nz(); z++) {
            for (int y = 0; y < shape.ny(); y++) {
                double ux = shear == null
                        ? physics.initialVelocity().x()
                        : shear.amplitude()
                                * Math.sin(2 * Math.PI * shear.modeY() * y / shape.ny())
                                * Math.cos(2 * Math.PI * shear.modeZ() * z / shape.nz());
                for (int x = 0; x < shape.nx(); x++) {
                    if (x % 256 == 0 && Thread.currentThread().isInterrupted()) {
                        throw new SimulationException(0, x, y, z, "interrupted during initialization");
                    }
                    int index = x + shape.nx() * (y + shape.ny() * z);
                    if (state.geometry().isSolid(index)) {
                        continue;
                    }
                    for (int i = 0; i < D3Q19.Q; i++) {
                        populations[i][index] = D3Q19.equilibrium(
                                i,
                                physics.densityReference(),
                                ux,
                                physics.initialVelocity().y(),
                                physics.initialVelocity().z());
                        var a = physics.acceleration();
                        if (a.x() != 0 || a.y() != 0 || a.z() != 0) {
                            populations[i][index] += D3Q19.guo(
                                            i,
                                            physics.densityReference(),
                                            ux,
                                            physics.initialVelocity().y(),
                                            physics.initialVelocity().z(),
                                            a.x(),
                                            a.y(),
                                            a.z())
                                    / 2;
                        }
                    }
                }
            }
        }
        state.initialized(FieldExtractor.summarize(
                populations,
                shape,
                physics.densityReference(),
                0,
                null,
                state.geometry(),
                physics.acceleration(),
                configuration.config().physics().guards(),
                diagnosticScratch));
    }
}
