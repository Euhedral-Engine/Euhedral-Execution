package io.euhedral_execution.benchmarks.cfd.solver;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.*;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.*;
import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Isolated
class OpenBoundariesTest {
    private static ControlPlaneLattice lattice;

    @BeforeAll
    static void startRuntime() {
        lattice = CfdTestRuntime.singleWorker();
    }

    @AfterAll
    static void closeRuntime() {
        lattice.close();
    }

    static Faces faces(int inlet, boolean walls) {
        var conditions = new FaceCondition[6];
        if (walls) java.util.Arrays.fill(conditions, FaceCondition.WALL);
        conditions[inlet] = FaceCondition.VELOCITY_INLET;
        conditions[inlet ^ 1] = FaceCondition.DENSITY_OUTLET;
        return new Faces(conditions[0], conditions[1], conditions[2], conditions[3], conditions[4], conditions[5]);
    }

    static Vector3 velocity(int face, double speed) {
        double signed = OpenBoundaries.inwardSign(face) * speed;
        return new Vector3(face / 2 == 0 ? signed : 0, face / 2 == 1 ? signed : 0, face / 2 == 2 ? signed : 0);
    }

    static CfdConfiguration config(int face, boolean walls, double ramp, long steps) {
        var velocity = velocity(face, walls ? 0.003 : 0.012);
        return ConfigLoader.resolve(
                Path.of("open.json"),
                new SimulationConfig(
                        1,
                        new GridShape(7, 8, 9),
                        new Physics(null, new Lattice(0.1, walls ? Vector3.ZERO : velocity, null), null, null),
                        new Geometry(faces(face, walls), null, null, null, new OpenBoundary(velocity, 1.0, ramp)),
                        new Execution(steps, null, null, null),
                        null,
                        null),
                100_000_000);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void reconstructionPreservesKnownPopulationsAndImposesAllMoments(int face) {
        for (boolean pressure : new boolean[] {false, true}) {
            double[] f = new double[19];
            double[] u = {0.007, -0.009, 0.011};
            for (int i = 0; i < 19; i++) f[i] = D3Q19.weight(i) * (1 + 0.003 * Math.sin(13 * i));
            double known = 0;
            int axis = face / 2, sign = OpenBoundaries.inwardSign(face);
            for (int i = 0; i < 19; i++) {
                int normal = OpenBoundaries.component(i, axis) * sign;
                if (normal == 0) known += f[i];
                if (normal < 0) known += 2 * f[i];
                if (normal > 0) f[i] = Double.NaN;
            }
            double[] before = f.clone();
            double rho = pressure ? 1.01 : known / (1 - sign * u[axis]);
            OpenBoundaries.reconstruct(f, face, pressure ? rho : 0, u[0], u[1], u[2]);
            if (pressure) u[axis] = sign * (1 - known / rho);
            double mass = 0;
            double[] momentum = new double[3];
            int missing = 0;
            for (int i = 0; i < 19; i++) {
                if (OpenBoundaries.missing(face, i)) missing++;
                else assertEquals(before[i], f[i], 0);
                mass += f[i];
                for (int a = 0; a < 3; a++) momentum[a] += OpenBoundaries.component(i, a) * f[i];
            }
            assertEquals(5, missing);
            assertEquals(rho, mass, 5e-16);
            for (int a = 0; a < 3; a++) assertEquals(rho * u[a], momentum[a], 1e-16);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void uniformThroughFlowRemainsUniformOnEveryOrientation(int face) {
        var config = config(face, false, 0, 30);
        var u = config.physics().initialVelocity();
        try (var simulation = new SerialSimulation(config, lattice)) {
            var state = simulation.run();
            for (int z = 0; z < 9; z++)
                for (int y = 0; y < 8; y++)
                    for (int x = 0; x < 7; x++) {
                        var field = state.field(x, y, z);
                        assertEquals(1, field.density(), 5e-15);
                        assertEquals(u.x(), field.ux(), 5e-15);
                        assertEquals(u.y(), field.uy(), 5e-15);
                        assertEquals(u.z(), field.uz(), 5e-15);
                    }
            var flow = state.flowDiagnostics();
            double area = 504.0 / (face / 2 == 0 ? 7 : face / 2 == 1 ? 8 : 9);
            assertEquals(0.012 * area, flow.inletFlux(), 2e-13);
            assertEquals(flow.inletFlux(), flow.outletFlux(), 2e-13);
            assertEquals(flow.inletFlux(), flow.macroscopicInletFlux(), 2e-13);
            assertEquals(0, flow.massBalanceResidual(), 2e-13);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void rampedDuctHandlesSolidPerimetersAndBalancesEachStep(int face) {
        var config = config(face, true, 25, 150);
        try (var simulation = new SerialSimulation(config, lattice)) {
            var state = simulation.state();
            var shape = state.shape();
            var open = state.geometry().openBoundaries();
            double previousMass = state.diagnostics().mass();
            for (int step = 1; step <= 150; step++) {
                simulation.step();
                var flow = state.flowDiagnostics();
                assertEquals(state.diagnostics().mass() - previousMass, flow.massChange(), 8e-13);
                assertEquals(0, flow.massBalanceResidual(), 8e-13);
                previousMass = state.diagnostics().mass();
                for (int z = 0; z < 9; z++)
                    for (int y = 0; y < 8; y++)
                        for (int x = 0; x < 7; x++) {
                            if (state.geometry().isSolid(x + 7 * (y + 8 * z))) continue;
                            int boundary = open.face(x, y, z, shape);
                            if (boundary < 0) continue;
                            var field = state.field(x, y, z);
                            if (open.isInlet(boundary)) {
                                double factor = Math.min(1, step / 25.0);
                                assertEquals(factor * open.velocity(0), field.ux(), 2e-16);
                                assertEquals(factor * open.velocity(1), field.uy(), 2e-16);
                                assertEquals(factor * open.velocity(2), field.uz(), 2e-16);
                            } else assertEquals(1, field.density(), 1e-15);
                        }
            }
            assertTrue(state.flowDiagnostics().outletFlux() > 0);
            assertTrue(state.diagnostics().maxMach() < 0.03);
            int expected = (face / 2 == 0 ? 7 : 5) * (face / 2 == 1 ? 8 : 6) * (face / 2 == 2 ? 9 : 7);
            assertEquals(expected, state.geometry().fluidCells());
        }
    }

    @Test
    void physicalBoundaryRampAndDragReferencesResolveToTheSameLatticeCase() {
        var base = config(0, true, 25, 30);
        var physical = ConfigLoader.resolve(
                Path.of("physical-open.json"),
                new SimulationConfig(
                        1,
                        base.config().grid(),
                        new Physics(
                                null,
                                null,
                                new Physical(0.01, 0.001, 1000, 0.01, null, null),
                                null,
                                null,
                                null,
                                new ForceReference(0.03, 0.0004, 1000, new Vector3(3, 0, 0))),
                        new Geometry(
                                faces(0, true),
                                null,
                                null,
                                null,
                                new OpenBoundary(new Vector3(0.03, 0, 0), 1000.0, 0.025)),
                        base.config().execution(),
                        null,
                        null),
                100_000_000);
        var open = OpenBoundaries.resolve(physical);
        assertEquals(25, open.rampSteps(), 0);
        assertEquals(1, open.outletDensity(), 0);
        assertEquals(0.003, open.velocity(0), 1e-18);
        assertEquals(
                0.5 * 0.003 * 0.003 * 4,
                FlowDiagnostics.validateReference(physical.config().physics().forceReference(), physical.physics()),
                1e-20);
        try (var a = new SerialSimulation(base, lattice);
                var b = new SerialSimulation(physical, lattice)) {
            a.run();
            b.run();
            for (int i = 0; i < 19; i++)
                assertArrayEquals(a.state().current()[i], b.state().current()[i], 3e-15);
        }
    }

    @Test
    void openAndObstacleLinksCrossRangeFacesEdgesAndCorners() {
        var base = config(0, true, 0, 1).config();
        var configuration = ConfigLoader.resolve(
                Path.of("split-open.json"),
                new SimulationConfig(
                        1,
                        base.grid(),
                        base.physics(),
                        new Geometry(
                                base.geometry().faces(),
                                null,
                                List.of(new Sphere(9, new Vector3(3.5, 4, 4.5), 1.2)),
                                null,
                                base.geometry().openBoundary()),
                        base.execution(),
                        null,
                        null),
                100_000_000);
        try (var simulation = new SerialSimulation(configuration, lattice)) {
            var state = simulation.state();
            var split = new PopulationGrid(configuration);
            var context = new StepContext(
                    state.shape(),
                    state.current(),
                    split.buffer(0),
                    1 / configuration.physics().tau(),
                    1,
                    System.nanoTime(),
                    30_000_000_000L,
                    System::nanoTime,
                    state.geometry(),
                    Vector3.ZERO,
                    1,
                    Guards.DEFAULT);
            var ranges = new CfdRangeFrame[8];
            int[] xs = {0, 3, 7}, ys = {0, 4, 8}, zs = {0, 4, 9};
            int id = 0;
            for (int z = 0; z < 2; z++)
                for (int y = 0; y < 2; y++)
                    for (int x = 0; x < 2; x++) {
                        var frame = new CfdRangeFrame(1, null);
                        frame.replace(context, id, xs[x], xs[x + 1], ys[y], ys[y + 1], zs[z], zs[z + 1]);
                        frame.execute();
                        frame.doFinally();
                        ranges[id++] = frame;
                    }
            var flow = new FlowDiagnostics(state.geometry(), null, configuration.physics());
            flow.reduce(1, ranges);
            simulation.step();
            for (int i = 0; i < 19; i++) assertArrayEquals(state.current()[i], split.buffer(0)[i], 0);
            assertEquals(state.flowDiagnostics().inletFlux(), flow.inletFlux(), 1e-15);
            assertEquals(state.flowDiagnostics().outletFlux(), flow.outletFlux(), 1e-15);
            for (int axis = 0; axis < 3; axis++)
                assertEquals(state.flowDiagnostics().force(9, axis), flow.force(9, axis), 3e-15);
            assertEquals(0, flow.massBalanceResidual(), 1e-13);
        }
    }

    @Test
    void failedGenerationDoesNotPublishNewFlowDiagnostics() {
        var config = config(0, false, 0, 2);
        try (var simulation = new SerialSimulation(config, lattice)) {
            simulation.step();
            double inlet = simulation.state().flowDiagnostics().inletFlux();
            simulation.state().current()[0][10] = Double.NaN;
            assertThrows(SimulationException.class, simulation::step);
            assertEquals(1, simulation.state().completedSteps());
            assertEquals(1, simulation.state().flowDiagnostics().step());
            assertEquals(inlet, simulation.state().flowDiagnostics().inletFlux(), 0);
        }
    }

    @Test
    void rejectsUnsupportedIntersectionsForcingAndGeometry() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Geometry(
                        new Faces(
                                FaceCondition.VELOCITY_INLET,
                                FaceCondition.DENSITY_OUTLET,
                                FaceCondition.VELOCITY_INLET,
                                FaceCondition.DENSITY_OUTLET,
                                null,
                                null),
                        null,
                        null,
                        null,
                        new OpenBoundary(new Vector3(0.01, 0, 0), null, null)));
        assertThrows(IllegalArgumentException.class, () -> new Geometry(faces(0, false)));
        var base = config(0, false, 0, 1).config();
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigLoader.resolve(
                        Path.of("bad.json"),
                        new SimulationConfig(
                                1,
                                base.grid(),
                                new Physics(null, new Lattice(null, null, new Vector3(1e-6, 0, 0)), null, null),
                                base.geometry(),
                                base.execution(),
                                null,
                                null),
                        100_000_000));
        var touching = new Geometry(
                faces(0, false),
                List.of(new Box(1, Vector3.ZERO, new Vector3(2, 2, 2))),
                null,
                null,
                base.geometry().openBoundary());
        var badGeometry = ConfigLoader.resolve(
                Path.of("touching.json"),
                new SimulationConfig(1, base.grid(), base.physics(), touching, base.execution(), null, null),
                100_000_000);
        assertThrows(IllegalArgumentException.class, () -> new SerialSimulation(badGeometry, lattice));
    }
}
