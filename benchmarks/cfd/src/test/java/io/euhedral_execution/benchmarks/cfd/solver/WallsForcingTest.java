package io.euhedral_execution.benchmarks.cfd.solver;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.*;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.*;
import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Isolated
class WallsForcingTest {
    private static ControlPlaneLattice lattice;
    private static final Faces CHANNEL = new Faces(null, null, FaceCondition.WALL, FaceCondition.WALL, null, null);
    private static final Faces CLOSED = new Faces(
            FaceCondition.WALL,
            FaceCondition.WALL,
            FaceCondition.WALL,
            FaceCondition.WALL,
            FaceCondition.WALL,
            FaceCondition.WALL);

    @BeforeAll
    static void startRuntime() {
        lattice = CfdTestRuntime.singleWorker();
    }

    @AfterAll
    static void closeRuntime() {
        lattice.close();
    }

    private static CfdConfiguration config(
            GridShape shape,
            double nu,
            Vector3 velocity,
            Vector3 acceleration,
            Geometry geometry,
            long steps,
            Guards guards,
            long diagnosticsInterval) {
        return ConfigLoader.resolve(
                Path.of("test.json"),
                new SimulationConfig(
                        1,
                        shape,
                        new Physics(1.0, new Lattice(nu, velocity, acceleration), null, null, null, guards),
                        geometry,
                        new Execution(steps, null, 30_000L, null, diagnosticsInterval),
                        null,
                        null),
                100_000_000);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.03, 0.1, 0.3})
    void forcedInitializationAndStoredFieldsAdvanceByExactlyTheAcceleration(double nu) {
        for (int sign : new int[] {-1, 1}) {
            var velocity = new Vector3(0.004, -0.003, 0.002);
            var a = new Vector3(sign * 1e-5, sign * -2e-5, sign * 3e-5);
            try (var simulation =
                    new SerialSimulation(config(new GridShape(4, 5, 6), nu, velocity, a, null, 10, null, 1), lattice)) {
                for (int step = 0; step <= 10; step++) {
                    var state = simulation.state();
                    var field = state.field(1, 2, 3);
                    assertEquals(1, field.density(), 4e-14);
                    assertEquals(velocity.x() + step * a.x(), field.ux(), 3e-15);
                    assertEquals(velocity.y() + step * a.y(), field.uy(), 3e-15);
                    assertEquals(velocity.z() + step * a.z(), field.uz(), 3e-15);
                    double mx = 0;
                    for (int q = 0; q < 19; q++) mx += D3Q19.x(q) * state.population(q, 1, 2, 3);
                    assertEquals(field.density() * (field.ux() + a.x() / 2), mx, 2e-16);
                    if (step < 10) simulation.step();
                }
                assertEquals(120, simulation.state().diagnostics().mass(), 3e-11);
            }
        }
    }

    @Test
    void guoSourceHasZeroMassAndTheRequiredFirstAndSecondMoments() {
        double rho = 1.3;
        double[] u = {0.01, -0.02, 0.003}, a = {0.0001, 0.0002, -0.0003};
        double mass = 0;
        double[] momentum = new double[3];
        double[][] second = new double[3][3];
        for (int q = 0; q < 19; q++) {
            double value = D3Q19.guo(q, rho, u[0], u[1], u[2], a[0], a[1], a[2]);
            mass += value;
            int[] c = {D3Q19.x(q), D3Q19.y(q), D3Q19.z(q)};
            for (int i = 0; i < 3; i++) {
                momentum[i] += c[i] * value;
                for (int j = 0; j < 3; j++) second[i][j] += c[i] * c[j] * value;
            }
        }
        assertEquals(0, mass, 1e-19);
        for (int i = 0; i < 3; i++) {
            assertEquals(rho * a[i], momentum[i], 2e-19);
            for (int j = 0; j < 3; j++) assertEquals(rho * (u[i] * a[j] + u[j] * a[i]), second[i][j], 1e-19);
        }
    }

    @Test
    void explicitZeroForceMatchesTheUnforcedSolverExactly() {
        var shape = new GridShape(4, 5, 6);
        var velocity = new Vector3(0.01, -0.02, 0.003);
        try (var baseline = new SerialSimulation(config(shape, 0.1, velocity, null, null, 8, null, 1), lattice);
                var zero =
                        new SerialSimulation(config(shape, 0.1, velocity, Vector3.ZERO, null, 8, null, 1), lattice)) {
            baseline.run();
            zero.run();
            for (int q = 0; q < 19; q++)
                assertArrayEquals(baseline.state().current()[q], zero.state().current()[q], 0);
        }
    }

    @Test
    void physicalFieldsUseTheSameRepresentedTimeAsLatticeFields() {
        var physical = new Physics(
                2.0, null, new Physical(0.01, 0.001, 1000, 0.01, new Vector3(0.1, 0, 0), new Vector3(0.2, 0, 0)), null);
        var config = ConfigLoader.resolve(
                Path.of("physical.json"),
                new SimulationConfig(
                        1, new GridShape(3, 4, 5), physical, null, new Execution(2L, null, null, null), null, null),
                100_000_000);
        try (var simulation = new SerialSimulation(config, lattice)) {
            assertEquals(0.1, simulation.state().physicalField(0, 0, 0).ux(), 1e-14);
            var field = simulation.run().physicalField(0, 0, 0);
            assertEquals(1000, field.density(), 1e-10);
            assertEquals(0.1 + 0.2 * 0.002, field.ux(), 1e-14);
            assertEquals(0, field.gaugePressure(), 1e-8);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stationarySolidsPreserveRestAndFluidMass(boolean closed) {
        var geometry = new Geometry(
                closed ? CLOSED : null,
                List.of(new Box(7, new Vector3(1, 1, 1), new Vector3(2, 3, 4))),
                List.of(new Sphere(2, new Vector3(4, 4, 4), 1.2)),
                List.of(new Cylinder(9, new Vector3(1, 5, 2), new Vector3(5, 5, 5), 0.6)));
        try (var simulation = new SerialSimulation(
                config(new GridShape(7, 8, 9), 0.1, Vector3.ZERO, Vector3.ZERO, geometry, 30, null, 1), lattice)) {
            var state = simulation.run();
            assertEquals(state.geometry().fluidCells(), state.diagnostics().mass(), 5e-11);
            assertEquals(0, state.diagnostics().maxSpeed(), 1e-15);
            for (int z = 0; z < 9; z++)
                for (int y = 0; y < 8; y++)
                    for (int x = 0; x < 7; x++) {
                        boolean solid = state.geometry().isSolid(x + 7 * (y + 8 * z));
                        for (int q = 0; q < 19; q++)
                            assertEquals(solid ? 0 : D3Q19.weight(q), state.population(q, x, y, z), 3e-15);
                    }
            assertThrows(IllegalArgumentException.class, () -> state.field(1, 1, 1));
        }
    }

    @Test
    void periodicObstacleConservesMassUnderForcing() throws Exception {
        try (var simulation =
                new SerialSimulation(ConfigLoader.load(Path.of("scenes/periodic-obstacle.json")), lattice)) {
            var state = simulation.run();
            assertEquals(state.geometry().fluidCells(), state.diagnostics().mass(), 1e-8);
            assertTrue(state.diagnostics().maxSpeed() > 0);
        }
    }

    @Test
    void closedDomainConservesMassWhileVelocityAndDensityEvolve() {
        try (var simulation = new SerialSimulation(
                config(
                        new GridShape(5, 7, 9),
                        0.1,
                        new Vector3(0.005, -0.004, 0.003),
                        new Vector3(1e-5, 0, 0),
                        new Geometry(CLOSED),
                        80,
                        null,
                        1),
                lattice)) {
            var state = simulation.run();
            assertEquals(315, state.diagnostics().mass(), 1e-9);
            assertTrue(state.diagnostics().maxDensity() > state.diagnostics().minDensity());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allMovingLinksReflectLocallyAndNeverReadSolidPopulations(boolean domainWall) {
        var shape = new GridShape(7, 7, 7);
        for (int q = 1; q < 19; q++) {
            int x = domainWall ? edge(D3Q19.x(q)) : 3;
            int y = domainWall ? edge(D3Q19.y(q)) : 3;
            int z = domainWall ? edge(D3Q19.z(q)) : 3;
            int sx = x - D3Q19.x(q), sy = y - D3Q19.y(q), sz = z - D3Q19.z(q);
            var geometry = domainWall
                    ? new Geometry(CLOSED)
                    : new Geometry(
                            null,
                            List.of(new Box(1, new Vector3(sx, sy, sz), new Vector3(sx + 1, sy + 1, sz + 1))),
                            null,
                            null);
            var resolved = config(shape, 0.1, Vector3.ZERO, Vector3.ZERO, geometry, 1, null, 1);
            var mask = GeometryMask.resolve(resolved);
            double[][] current = new double[19][343], next = new double[19][343];
            for (int i = 0; i < 19; i++) {
                Arrays.fill(current[i], D3Q19.weight(i));
                Arrays.fill(next[i], -999);
                if (!domainWall) current[i][sx + 7 * (sy + 7 * sz)] = Double.NaN;
            }
            int destination = x + 7 * (y + 7 * z);
            current[D3Q19.opposite(q)][destination] += 0.001;
            var context = new StepContext(
                    shape,
                    current,
                    next,
                    1e-100,
                    1,
                    0,
                    Long.MAX_VALUE,
                    () -> 0,
                    mask,
                    Vector3.ZERO,
                    1,
                    new Guards(null, null));
            var frame = new CfdRangeFrame(1, null);
            frame.replace(context, x, x + 1, y, y + 1, z, z + 1);
            frame.execute();
            frame.doFinally();
            frame.requireSuccess();
            assertEquals(D3Q19.weight(q) + 0.001, next[q][destination], 0, "direction=" + q);
            for (int cell = 0; cell < 343; cell++)
                if (cell != destination) for (int i = 0; i < 19; i++) assertEquals(-999, next[i][cell], 0);
        }
    }

    private static int edge(int component) {
        return component > 0 ? 0 : component < 0 ? 6 : 3;
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.05, 0.1, 0.2})
    void channelApproachesPoiseuilleProfileUnderDiffusiveRefinement(double nu) {
        double coarse = channelError(8, nu), fine = channelError(16, nu);
        assertTrue(coarse < 0.03, "coarse relative L2=" + coarse);
        assertTrue(fine < 0.008, "fine relative L2=" + fine);
        assertTrue(fine < coarse * 0.4, "refinement must reduce error: " + coarse + " -> " + fine);
    }

    private static double channelError(int height, double nu) {
        double peak = 0.02 * 8 / height;
        double acceleration = 8 * nu * peak / (height * height);
        long steps = (long) Math.ceil(12 * height * height / (Math.PI * Math.PI * nu));
        try (var simulation = new SerialSimulation(
                config(
                        new GridShape(3, height, 3),
                        nu,
                        Vector3.ZERO,
                        new Vector3(acceleration, 0, 0),
                        new Geometry(CHANNEL),
                        steps,
                        null,
                        0),
                lattice)) {
            var state = simulation.run();
            double error = 0, norm = 0;
            for (int y = 0; y < height; y++) {
                /// The wall planes are y=0 and y=H; fluid centers are half a cell from each wall.
                double position = y + 0.5;
                double expected = acceleration * position * (height - position) / (2 * nu);
                var field = state.field(1, y, 1);
                error += Math.pow(field.ux() - expected, 2);
                norm += expected * expected;
                assertEquals(0, field.uy(), 1e-13);
                assertEquals(0, field.uz(), 1e-13);
                assertEquals(field.ux(), state.field(0, y, 2).ux(), 1e-14);
            }
            assertEquals(state.geometry().fluidCells(), state.diagnostics().mass(), 1e-8);
            return Math.sqrt(error / norm);
        }
    }

    @Test
    void optionalDiagnosticsDoNotDisableGuardsOrPublishFailedGenerations() {
        var config = config(
                new GridShape(3, 4, 5), 0.1, Vector3.ZERO, new Vector3(0.02, 0, 0), null, 10, new Guards(0.05, 0.1), 0);
        try (var simulation = new SerialSimulation(config, lattice)) {
            simulation.step();
            var state = simulation.state();
            assertEquals(1, state.completedSteps());
            assertEquals(0, state.diagnostics().step(), "diagnostics retain the last sampled step");
            double[] before = state.current()[1].clone();
            var error = assertThrows(SimulationException.class, simulation::step);
            assertEquals(2, error.step());
            assertTrue(error.getMessage().contains("Mach"));
            assertEquals(1, state.completedSteps());
            assertArrayEquals(before, state.current()[1], 0);
        }
    }

    @Test
    void densityGuardAndInitialMachGuardIncludeCellAndGeneration() {
        var config =
                config(new GridShape(3, 4, 5), 0.1, Vector3.ZERO, Vector3.ZERO, null, 3, new Guards(null, 0.01), 0);
        try (var simulation = new SerialSimulation(config, lattice)) {
            simulation.state().current()[0][0] += 0.02;
            var error = assertThrows(SimulationException.class, simulation::step);
            assertEquals(1, error.step());
            assertEquals(0, error.x());
            assertEquals(0, error.y());
            assertEquals(0, error.z());
            assertTrue(error.getMessage().contains("density"));
            assertEquals(0, simulation.state().completedSteps());
        }
        var fast = config(new GridShape(3, 4, 5), 0.1, new Vector3(0.1, 0, 0), Vector3.ZERO, null, 1, null, 1);
        assertEquals(
                0,
                assertThrows(SimulationException.class, () -> new SerialSimulation(fast, lattice))
                        .step());
    }
}
