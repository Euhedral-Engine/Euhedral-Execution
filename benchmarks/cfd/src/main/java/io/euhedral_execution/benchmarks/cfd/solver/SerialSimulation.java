package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/// Owns setup and generation boundaries; the reusable range frame owns parallelizable work.
public final class SerialSimulation implements AutoCloseable {
    private final CfdConfiguration configuration;
    private final SimulationState state;
    private final QueueIngestSink sink = new QueueIngestSink();
    private final CfdRangeFrame rangeFrame;
    private final CfdRangeFrame[] ranges;
    private FlowDiagnostics pendingFlow;
    private final LongSupplier clock;
    private final double[] diagnosticScratch = new double[5];
    private boolean failed;
    private boolean closed;

    /// The caller owns the lattice and closes it after all simulations using it have closed.
    public SerialSimulation(CfdConfiguration configuration, ControlPlaneLattice lattice) {
        this(configuration, lattice, System::nanoTime);
    }

    SerialSimulation(CfdConfiguration configuration, ControlPlaneLattice lattice, LongSupplier clock) {
        requireSupported(configuration);
        this.configuration = configuration;
        this.clock = Objects.requireNonNull(clock);
        var geometry = GeometryMask.resolve(configuration);
        state = new SimulationState(
                new PopulationGrid(configuration),
                configuration.physics(),
                geometry,
                new FlowDiagnostics(
                        geometry, configuration.config().physics().forceReference(), configuration.physics()));
        pendingFlow = new FlowDiagnostics(
                geometry, configuration.config().physics().forceReference(), configuration.physics());
        initialize();
        rangeFrame = new CfdRangeFrame(1, null);
        ranges = new CfdRangeFrame[] {rangeFrame};
        Objects.requireNonNull(lattice).addUpstream(sink);
    }

    public static void requireSupported(CfdConfiguration configuration) {
        if (configuration.config().output().exportEverySteps() != 0)
            throw new IllegalArgumentException("field export is not implemented; exportEverySteps must be 0");
    }

    public SimulationState state() {
        return state;
    }

    public SimulationState run() {
        while (state.completedSteps() < configuration.steps()) step();
        return state;
    }

    public void step() {
        if (closed) throw new IllegalStateException("simulation is closed");
        if (failed) throw new IllegalStateException("simulation has failed; last completed state remains available");
        if (state.completedSteps() >= configuration.steps())
            throw new IllegalStateException("configured duration completed");
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
            rangeFrame.replace(
                    context,
                    0,
                    state.shape().nx(),
                    0,
                    state.shape().ny(),
                    0,
                    state.shape().nz());
            /// The unchanged identity/routing hash keeps all work from this sink on one FIFO lane.
            /// Parallel range dispatch uses randomizeHash(seed) before offering each frame.
            context.checkProgress(0, 0, 0);
            while (!sink.offer(rangeFrame)) {
                context.checkProgress(0, 0, 0);
                LockSupport.parkNanos(10_000);
            }
            while (!rangeFrame.isDone()) {
                context.checkProgress(0, 0, 0);
                LockSupport.parkNanos(10_000);
            }
            rangeFrame.requireSuccess();
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
            pendingFlow.reduce(context.step(), ranges);
            context.checkProgress(0, 0, 0);
            var previousFlow = state.flowDiagnostics();
            state.complete(context.step(), diagnostics, pendingFlow);
            pendingFlow = previousFlow;
        } catch (RuntimeException e) {
            failed = true;
            rangeFrame.kill();
            throw e;
        }
    }

    /// Stops this source. The runtime owner closes the lattice to quiesce outstanding workers.
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        rangeFrame.kill();
        sink.complete();
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
                    if (x % 256 == 0 && Thread.currentThread().isInterrupted())
                        throw new SimulationException(0, x, y, z, "interrupted during initialization");
                    int index = x + shape.nx() * (y + shape.ny() * z);
                    if (state.geometry().isSolid(index)) continue;
                    for (int i = 0; i < D3Q19.Q; i++) {
                        populations[i][index] = D3Q19.equilibrium(
                                i,
                                physics.densityReference(),
                                ux,
                                physics.initialVelocity().y(),
                                physics.initialVelocity().z());
                        var a = physics.acceleration();
                        if (a.x() != 0 || a.y() != 0 || a.z() != 0)
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
