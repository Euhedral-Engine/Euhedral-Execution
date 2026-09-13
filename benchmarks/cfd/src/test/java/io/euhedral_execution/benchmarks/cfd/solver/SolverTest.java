package io.euhedral_execution.benchmarks.cfd.solver;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig;
import io.euhedral_execution.benchmarks.cfd.config.Vector3;
import io.euhedral_execution.benchmarks.cfd.frames.CfdFrame;
import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class SolverTest {
    private static ControlPlaneLattice lattice;
    private final List<SerialSimulation> simulations = new ArrayList<>();

    @BeforeAll
    static void startRuntime() {
        lattice = CfdTestRuntime.singleWorker();
    }

    @AfterAll
    static void closeRuntime() {
        lattice.close();
    }

    @AfterEach
    void closeSimulations() {
        simulations.forEach(SerialSimulation::close);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!lattice.isDrained()) {
            if (System.nanoTime() - deadline >= 0) fail("runtime did not become quiescent");
            LockSupport.parkNanos(10_000);
        }
    }

    private SerialSimulation simulation(CfdConfiguration config) {
        return simulation(config, System::nanoTime);
    }

    private SerialSimulation simulation(CfdConfiguration config, LongSupplier clock) {
        var simulation = new SerialSimulation(config, lattice, clock);
        simulations.add(simulation);
        return simulation;
    }

    private static CfdConfiguration config(
            GridShape shape, double viscosity, Vector3 velocity, long steps, SimulationConfig.Shear shear) {
        var physics = new SimulationConfig.Physics(
                1.0, new SimulationConfig.Lattice(viscosity, velocity, null), null, null, shear);
        return ConfigLoader.resolve(
                Path.of("test.json"),
                new SimulationConfig(
                        1,
                        shape,
                        physics,
                        null,
                        new SimulationConfig.Execution(steps, null, 30_000L, null),
                        null,
                        null),
                100_000_000);
    }

    private static StepContext context(GridShape shape, double[][] current, double[][] next, double omega, long step) {
        return new StepContext(shape, current, next, omega, step, 0, Long.MAX_VALUE, () -> 0);
    }

    private static void update(StepContext context) {
        var shape = context.shape();
        update(context, 0, shape.nx(), 0, shape.ny(), 0, shape.nz());
    }

    private static void update(StepContext context, int xFrom, int xTo, int yFrom, int yTo, int zFrom, int zTo) {
        var frame = new CfdRangeFrame(1, null);
        frame.replace(context, xFrom, xTo, yFrom, yTo, zFrom, zTo);
        frame.execute();
        frame.doFinally();
        frame.requireSuccess();
    }

    private static double[][] buffers(GridShape shape) {
        return new double[19][Math.toIntExact(shape.cellCount())];
    }

    private static double[][] copy(double[][] input) {
        return Arrays.stream(input).map(double[]::clone).toArray(double[][]::new);
    }

    private static void assertPopulations(double[][] expected, double[][] actual, double tolerance) {
        for (int i = 0; i < 19; i++) assertArrayEquals(expected[i], actual[i], tolerance, "direction=" + i);
    }

    @Test
    void stencilOppositesAndIsotropicMoments() {
        var directions = new HashSet<String>();
        double mass = 0;
        for (int i = 0; i < 19; i++) {
            int[] c = {D3Q19.x(i), D3Q19.y(i), D3Q19.z(i)};
            assertTrue(directions.add(Arrays.toString(c)));
            assertEquals(i, D3Q19.opposite(D3Q19.opposite(i)));
            assertEquals(-c[0], D3Q19.x(D3Q19.opposite(i)));
            assertEquals(-c[1], D3Q19.y(D3Q19.opposite(i)));
            assertEquals(-c[2], D3Q19.z(D3Q19.opposite(i)));
            assertEquals(D3Q19.weight(i), D3Q19.weight(D3Q19.opposite(i)));
            mass += D3Q19.weight(i);
        }
        assertEquals(1, mass, 3e-16);
        for (int a = 0; a < 3; a++)
            for (int b = 0; b < 3; b++)
                for (int c = 0; c < 3; c++)
                    for (int d = 0; d < 3; d++) {
                        double first = 0, second = 0, third = 0, fourth = 0;
                        for (int i = 0; i < 19; i++) {
                            int[] v = {D3Q19.x(i), D3Q19.y(i), D3Q19.z(i)};
                            double w = D3Q19.weight(i);
                            first += w * v[a];
                            second += w * v[a] * v[b];
                            third += w * v[a] * v[b] * v[c];
                            fourth += w * v[a] * v[b] * v[c] * v[d];
                        }
                        assertEquals(0, first, 1e-15);
                        assertEquals(a == b ? 1.0 / 3 : 0, second, 1e-15);
                        assertEquals(0, third, 1e-15);
                        int contractions =
                                (a == b && c == d ? 1 : 0) + (a == c && b == d ? 1 : 0) + (a == d && b == c ? 1 : 0);
                        assertEquals(contractions / 9.0, fourth, 1e-15);
                    }
    }

    @Test
    void equilibriumHasPrescribedDensityAndMomentum() {
        double rho = 1.2, ux = 0.03, uy = -0.02, uz = 0.01;
        double mass = 0, mx = 0, my = 0, mz = 0;
        for (int i = 0; i < 19; i++) {
            double value = D3Q19.equilibrium(i, rho, ux, uy, uz);
            mass += value;
            mx += D3Q19.x(i) * value;
            my += D3Q19.y(i) * value;
            mz += D3Q19.z(i) * value;
        }
        assertEquals(rho, mass, 1e-15);
        assertEquals(rho * ux, mx, 1e-15);
        assertEquals(rho * uy, my, 1e-15);
        assertEquals(rho * uz, mz, 1e-15);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.03, 0.1, 0.3})
    void uniformFlowPreservesBothBuffersFieldsAndCompletedTime(double viscosity) {
        var shape = new GridShape(5, 7, 9);
        var velocity = new Vector3(0.01, -0.02, 0.03);
        var simulation = simulation(config(shape, viscosity, velocity, 12, null));
        double[][] initial = copy(simulation.state().current());
        var state = simulation.run();
        assertEquals(12, state.completedSteps());
        assertEquals(12, state.diagnostics().step());
        assertPopulations(initial, state.current(), 3e-15);
        assertEquals(shape.cellCount(), state.diagnostics().mass(), 2e-10);
        var field = state.field(4, 6, 8);
        assertEquals(1, field.density(), 3e-14);
        assertEquals(velocity.x(), field.ux(), 3e-15);
        assertEquals(velocity.y(), field.uy(), 3e-15);
        assertEquals(velocity.z(), field.uz(), 3e-15);
        assertEquals((field.density() - 1) / 3, field.gaugePressure(), 1e-17);
        assertEquals(velocity.magnitude() * Math.sqrt(3), state.diagnostics().maxMach(), 1e-14);
        assertSame(state, simulation.run());
        assertThrows(IllegalStateException.class, simulation::step);
    }

    @Test
    void everyMovingDirectionStreamsAcrossPeriodicFacesEdgesAndCorners() {
        var shape = new GridShape(4, 5, 6);
        for (int i = 1; i < 19; i++) {
            for (int sx : new int[] {0, 3})
                for (int sy : new int[] {0, 4})
                    for (int sz : new int[] {0, 5}) {
                        double[][] current = buffers(shape), next = buffers(shape);
                        for (int q = 0; q < 19; q++) Arrays.fill(current[q], D3Q19.weight(q));
                        int source = sx + 4 * (sy + 5 * sz);
                        current[i][source] += 0.001;
                        /// Negligible collision isolates streaming while retaining positive density everywhere.
                        update(context(shape, current, next, 1e-100, 1));
                        int target = Math.floorMod(sx + D3Q19.x(i), 4)
                                + 4 * (Math.floorMod(sy + D3Q19.y(i), 5) + 5 * Math.floorMod(sz + D3Q19.z(i), 6));
                        for (int q = 0; q < 19; q++)
                            for (int cell = 0; cell < shape.cellCount(); cell++) {
                                assertEquals(
                                        D3Q19.weight(q) + (q == i && cell == target ? 0.001 : 0), next[q][cell], 0);
                            }
                    }
        }
    }

    @Test
    void tinyGridAgreesWithIndependentPushThenCollideReferenceAcrossMultipleStepsAndRanges() {
        var shape = new GridShape(5, 4, 3);
        double[][] current = buffers(shape), next = buffers(shape);
        var random = new Random(144);
        for (int q = 0; q < 19; q++)
            for (int cell = 0; cell < shape.cellCount(); cell++)
                current[q][cell] = D3Q19.weight(q) * (0.98 + 0.04 * random.nextDouble());
        double[][] reference = copy(current);
        double omega = 1 / 0.83;
        for (int step = 1; step <= 7; step++) {
            double[][] old = copy(current);
            var context = context(shape, current, next, omega, step);
            /// Irregular ranges exercise all axes and leave boundary reads independent of ownership.
            for (int z = 0; z < 3; z += 2)
                for (int y = 0; y < 4; y += 3)
                    for (int x = 0; x < 5; x += 2)
                        update(context, x, Math.min(x + 2, 5), y, Math.min(y + 3, 4), z, Math.min(z + 2, 3));
            reference = referenceStep(shape, reference, omega);
            assertPopulations(reference, next, 8e-16);
            assertPopulations(old, current, 0);
            double[][] swap = current;
            current = next;
            next = swap;
        }
    }

    /// Independent AoS push-stream reference; no production direction, equilibrium, or indexing helpers.
    private static double[][] referenceStep(GridShape shape, double[][] input, double omega) {
        int[][] directions = {
            {0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}, {1, 1, 0}, {-1, -1, 0},
            {1, -1, 0}, {-1, 1, 0}, {1, 0, 1}, {-1, 0, -1}, {1, 0, -1}, {-1, 0, 1}, {0, 1, 1}, {0, -1, -1}, {0, 1, -1},
            {0, -1, 1}
        };
        int cells = (int) shape.cellCount();
        double[][] streamed = new double[cells][19];
        for (int source = 0; source < cells; source++) {
            int x = source % shape.nx(), y = source / shape.nx() % shape.ny(), z = source / (shape.nx() * shape.ny());
            for (int q = 0; q < 19; q++) {
                int tx = Math.floorMod(x + directions[q][0], shape.nx());
                int ty = Math.floorMod(y + directions[q][1], shape.ny());
                int tz = Math.floorMod(z + directions[q][2], shape.nz());
                streamed[(tz * shape.ny() + ty) * shape.nx() + tx][q] = input[q][source];
            }
        }
        double[][] output = new double[19][cells];
        for (int cell = 0; cell < cells; cell++) {
            double density = Arrays.stream(streamed[cell]).sum();
            double[] velocity = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                for (int q = 0; q < 19; q++) velocity[axis] += streamed[cell][q] * directions[q][axis];
                velocity[axis] /= density;
            }
            double speedSquared = Arrays.stream(velocity).map(v -> v * v).sum();
            for (int q = 0; q < 19; q++) {
                double projection = 0;
                int norm = 0;
                for (int axis = 0; axis < 3; axis++) {
                    projection += velocity[axis] * directions[q][axis];
                    norm += directions[q][axis] * directions[q][axis];
                }
                double weight = norm == 0 ? 1.0 / 3 : norm == 1 ? 1.0 / 18 : 1.0 / 36;
                double eq = weight
                        * density
                        * (1 + projection / (1.0 / 3) + projection * projection / (2.0 / 9) - speedSquared / (2.0 / 3));
                output[q][cell] = (1 - omega) * streamed[cell][q] + omega * eq;
            }
        }
        return output;
    }

    @Test
    void shearDecayApproachesAnalyticalSolutionUnderDiffusiveRefinement() {
        double coarse = shearError(12, 16, 10);
        double fine = shearError(24, 32, 40);
        System.out.println("Shear relative amplitude errors: coarse=" + coarse + ", fine=" + fine);
        assertTrue(coarse < 0.04, "coarse error=" + coarse);
        assertTrue(fine < 0.01, "fine error=" + fine);
        assertTrue(fine < coarse * 0.4, "refinement must reduce the error: " + coarse + " -> " + fine);
    }

    private double shearError(int ny, int nz, long steps) {
        double amplitude = 0.001, nu = 0.1;
        var shape = new GridShape(4, ny, nz);
        var simulation =
                simulation(config(shape, nu, Vector3.ZERO, steps, new SimulationConfig.Shear(amplitude, 1, 1)));
        assertEquals(
                amplitude * Math.sin(2 * Math.PI / ny),
                simulation.state().field(1, 1, 0).ux(),
                1e-15);
        assertEquals(0, simulation.state().field(1, 1, nz / 4).ux(), 1e-15);
        simulation.run();
        double dot = 0, norm = 0;
        for (int z = 0; z < nz; z++)
            for (int y = 0; y < ny; y++) {
                double basis = Math.sin(2 * Math.PI * y / ny) * Math.cos(2 * Math.PI * z / nz);
                var field = simulation.state().field(0, y, z);
                dot += field.ux() * basis;
                norm += basis * basis;
                /// The analytical shear solution is linearized; transverse finite-amplitude errors are O(A^2).
                assertEquals(0, field.uy(), 0.1 * amplitude * amplitude);
                assertEquals(0, field.uz(), 0.1 * amplitude * amplitude);
            }
        double k2 = Math.pow(2 * Math.PI / ny, 2) + Math.pow(2 * Math.PI / nz, 2);
        double expected = amplitude * Math.exp(-nu * k2 * steps);
        assertEquals(shape.cellCount(), simulation.state().diagnostics().mass(), 2e-9);
        return Math.abs(dot / norm - expected) / expected;
    }

    @Test
    void rangeWritesOnlyItsOwnedDestinations() {
        var shape = new GridShape(5, 6, 7);
        double[][] current = buffers(shape), next = buffers(shape);
        for (int q = 0; q < 19; q++) {
            Arrays.fill(current[q], D3Q19.weight(q));
            Arrays.fill(next[q], -999);
        }
        update(context(shape, current, next, 1.25, 1), 1, 4, 2, 5, 3, 6);
        for (int z = 0; z < 7; z++)
            for (int y = 0; y < 6; y++)
                for (int x = 0; x < 5; x++) {
                    boolean owned = x >= 1 && x < 4 && y >= 2 && y < 5 && z >= 3 && z < 6;
                    for (int q = 0; q < 19; q++)
                        assertEquals(owned ? D3Q19.weight(q) : -999, next[q][x + 5 * (y + 6 * z)], 1e-15);
                }
    }

    @Test
    void numericalFailuresHaveCellAndStepContextAndNeverModifyCurrent() {
        var shape = new GridShape(4, 5, 6);
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0}) {
            double[][] current = buffers(shape), next = buffers(shape);
            if (!Double.isFinite(invalid)) {
                for (int q = 0; q < 19; q++) Arrays.fill(current[q], D3Q19.weight(q));
                current[0][2 + 4 * (3 + 5 * 4)] = invalid;
            } else for (double[] row : current) Arrays.fill(row, invalid);
            double[][] before = copy(current);
            var error = assertThrows(SimulationException.class, () -> update(context(shape, current, next, 1.25, 7)));
            assertEquals(7, error.step());
            assertTrue(error.getMessage().contains("cell=("));
            if (!Double.isFinite(invalid)) {
                assertEquals(2, error.x());
                assertEquals(3, error.y());
                assertEquals(4, error.z());
            }
            assertPopulations(before, current, 0);
        }
    }

    @Test
    void deadlineAndInterruptionRetainLastCompletedState() {
        var clock = new AtomicLong(0);
        var shape = new GridShape(4, 5, 6);
        var simulation = simulation(config(shape, 0.1, Vector3.ZERO, 3, null), clock::get);
        simulation.step();
        double[][] before = copy(simulation.state().current());
        var diagnostics = simulation.state().diagnostics();
        /// Expire after the generation captures its start time, before it can be dispatched.
        var calls = new AtomicLong();
        var deadlineSimulation = simulation(
                config(shape, 0.1, Vector3.ZERO, 3, null), () -> calls.incrementAndGet() >= 2 ? 30_000_000_000L : 0);
        double[][] initial = copy(deadlineSimulation.state().current());
        var error = assertThrows(SimulationException.class, deadlineSimulation::step);
        assertTrue(error.getMessage().contains("deadline"));
        assertEquals(0, deadlineSimulation.state().completedSteps());
        assertPopulations(initial, deadlineSimulation.state().current(), 0);
        assertThrows(IllegalStateException.class, deadlineSimulation::step);
        /// Interrupt is preserved, and does not publish over a previously completed step.
        Thread.currentThread().interrupt();
        try {
            assertThrows(SimulationException.class, simulation::step);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, simulation.state().completedSteps());
            assertSame(diagnostics, simulation.state().diagnostics());
            assertPopulations(before, simulation.state().current(), 0);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void collisionOverflowAndInvalidStoredFieldsAreDiagnosed() {
        var shape = new GridShape(3, 4, 5);
        double[][] current = buffers(shape), next = buffers(shape);
        for (int q = 0; q < 19; q++) Arrays.fill(current[q], D3Q19.weight(q));
        Arrays.fill(current[1], 1e200);
        Arrays.fill(current[2], -1e200);
        /// Widen physical guards here to isolate overflow in the collision arithmetic itself.
        var overflowContext = new StepContext(
                shape,
                current,
                next,
                1.25,
                3,
                0,
                Long.MAX_VALUE,
                () -> 0,
                io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask.periodic(shape),
                Vector3.ZERO,
                1,
                new SimulationConfig.Guards(Double.MAX_VALUE, Double.MAX_VALUE));
        var error = assertThrows(SimulationException.class, () -> update(overflowContext));
        assertTrue(error.getMessage().contains("collision population"));
        assertEquals(3, error.step());
        double[][] invalidStored = buffers(shape);
        var densityError = assertThrows(
                SimulationException.class, () -> FieldExtractor.summarize(invalidStored, shape, 1, 8, null));
        assertTrue(densityError.getMessage().contains("stored density"));
        assertEquals(8, densityError.step());
        invalidStored[0][0] = Double.NaN;
        assertTrue(assertThrows(
                        SimulationException.class, () -> FieldExtractor.sample(invalidStored, shape, 1, 8, 0, 0, 0))
                .getMessage()
                .contains("stored population"));
    }

    @Test
    void numericalFailureAfterSuccessfulStepPreservesBufferIdentityAndStep() {
        var simulation = simulation(config(new GridShape(3, 4, 5), 0.1, Vector3.ZERO, 3, null));
        simulation.step();
        double[][] current = simulation.state().current();
        var diagnostics = simulation.state().diagnostics();
        /// Inject a broken rest population after a completed step to exercise the driver's numerical failure path.
        current[0][7] = -100;
        double[][] snapshot = copy(current);
        assertEquals(
                2, assertThrows(SimulationException.class, simulation::step).step());
        assertEquals(1, simulation.state().completedSteps());
        assertSame(current, simulation.state().current());
        assertSame(diagnostics, simulation.state().diagnostics());
        assertPopulations(snapshot, current, 0);
        assertThrows(IllegalStateException.class, simulation::run);
    }

    @Test
    void invalidRangesAndAliasedBuffersAreRejectedBeforePreparation() {
        var shape = new GridShape(3, 4, 5);
        double[][] first = buffers(shape), second = buffers(shape);
        assertThrows(IllegalArgumentException.class, () -> context(shape, first, first, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> context(shape, first, second, 2, 1));
        var context = context(shape, first, second, 1, 1);
        var frame = new CfdRangeFrame(1, null);
        for (int[] bounds : new int[][] {
            {-1, 3, 0, 4, 0, 5}, {0, 3, -1, 4, 0, 5}, {0, 3, 0, 4, -1, 5},
            {0, 0, 0, 4, 0, 5}, {0, 3, 1, 1, 0, 5}, {0, 3, 0, 4, 2, 1},
            {0, 4, 0, 4, 0, 5}, {0, 3, 0, 5, 0, 5}, {0, 3, 0, 4, 0, 6}
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> frame.replace(context, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));
            assertEquals(CfdFrame.Status.NEW, frame.status());
        }
        assertThrows(NullPointerException.class, () -> frame.replace(null, 0, 3, 0, 4, 0, 5));
        frame.replace(context, 0, 3, 0, 4, 0, 5);
        assertEquals(CfdFrame.Status.READY, frame.status());
        double[][] aliased = second.clone();
        aliased[1] = first[0];
        assertThrows(IllegalArgumentException.class, () -> context(shape, first, aliased, 1, 1));
        aliased[1] = aliased[0];
        assertThrows(IllegalArgumentException.class, () -> context(shape, first, aliased, 1, 1));
    }
}
