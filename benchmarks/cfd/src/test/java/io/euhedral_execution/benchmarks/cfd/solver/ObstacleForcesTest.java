package io.euhedral_execution.benchmarks.cfd.solver;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.*;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.*;
import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameManager;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Isolated
class ObstacleForcesTest {
    private static ControlPlaneLattice lattice;

    @BeforeAll
    static void startRuntime() {
        lattice = CfdTestRuntime.singleWorker();
    }

    @AfterAll
    static void closeRuntime() {
        lattice.close();
    }

    static CfdConfiguration config(double speed) {
        return ConfigLoader.resolve(
                Path.of("forces.json"),
                new SimulationConfig(
                        1,
                        new GridShape(16, 8, 8),
                        new Physics(
                                null,
                                new Lattice(0.1, new Vector3(speed, 0, 0), null),
                                null,
                                null,
                                null,
                                null,
                                new ForceReference(0.01, 4, 1, new Vector3(2, 0, 0))),
                        new Geometry(
                                null,
                                List.of(
                                        new Box(42, new Vector3(3, 3, 3), new Vector3(5, 5, 5)),
                                        new Box(7, new Vector3(11, 3, 3), new Vector3(13, 5, 5))),
                                null,
                                null),
                        new Execution(20L, null, null, null),
                        null,
                        null),
                100_000_000);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 0.01, -0.01})
    void forcesAttributeBothObstaclesAndBalanceFluidMomentum(double speed) {
        try (var simulation = new SerialSimulation(config(speed), lattice)) {
            var state = simulation.state();
            double before = momentumX(state);
            for (int step = 1; step <= 20; step++) {
                simulation.step();
                var flow = state.flowDiagnostics();
                assertEquals(7, flow.obstacleId(0));
                assertEquals(42, flow.obstacleId(1));
                assertEquals(flow.force(7, 0), flow.force(42, 0), 3e-15);
                for (int id : new int[] {7, 42}) {
                    assertEquals(0, flow.force(id, 1), 2e-15);
                    assertEquals(0, flow.force(id, 2), 2e-15);
                    if (speed == 0) assertEquals(0, flow.force(id, 0), 2e-15);
                    else assertTrue(flow.force(id, 0) * speed > 0);
                    assertEquals(flow.force(id, 0) / 0.0002, flow.dragCoefficient(id), 1e-11);
                }
                double after = momentumX(state);
                assertEquals(before - after, flow.force(7, 0) + flow.force(42, 0), 2e-12);
                before = after;
                assertEquals(0, flow.massBalanceResidual(), 2e-12);
            }
        }
    }

    private static double momentumX(SimulationState state) {
        double sum = 0;
        for (int z = 0; z < 8; z++)
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 16; x++) {
                    if (state.geometry().isSolid(x + 16 * (y + 8 * z))) continue;
                    for (int q = 0; q < 19; q++) sum += D3Q19.x(q) * state.population(q, x, y, z);
                }
        return sum;
    }

    @Test
    void partitionedSlotsAgreeWithWholeRangeAndResetWhenReused() {
        var config = config(0.01);
        try (var simulation = new SerialSimulation(config, lattice)) {
            var state = simulation.state();
            var splitGrid = new PopulationGrid(config);
            var context = new StepContext(
                    state.shape(),
                    state.current(),
                    splitGrid.buffer(0),
                    1 / config.physics().tau(),
                    1,
                    System.nanoTime(),
                    30_000_000_000L,
                    System::nanoTime,
                    state.geometry(),
                    Vector3.ZERO,
                    1,
                    Guards.DEFAULT);
            var ranges = new CfdRangeFrame[3];
            int[] edges = {0, 4, 12, 16};
            for (int i = 0; i < ranges.length; i++) {
                ranges[i] = new CfdRangeFrame(1, null);
                ranges[i].replace(context, i, edges[i], edges[i + 1], 0, 8, 0, 8);
                assertThrows(IllegalStateException.class, ranges[i]::massChange);
                ranges[i].execute();
                ranges[i].doFinally();
            }
            var reduced = new FlowDiagnostics(
                    state.geometry(), config.config().physics().forceReference(), config.physics());
            reduced.reduce(1, ranges);
            simulation.step();
            for (int q = 0; q < 19; q++)
                assertArrayEquals(state.current()[q], splitGrid.buffer(0)[q], 0);
            for (int id : new int[] {7, 42})
                for (int a = 0; a < 3; a++)
                    assertEquals(state.flowDiagnostics().force(id, a), reduced.force(id, a), 3e-15);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> reduced.reduce(1, new CfdRangeFrame[] {ranges[1], ranges[0]}));
            assertThrows(IllegalArgumentException.class, () -> reduced.reduce(2, ranges));
            for (int i = 0; i < ranges.length; i++) {
                ranges[i].replace(context, i, edges[i], edges[i + 1], 0, 8, 0, 8);
                ranges[i].execute();
                ranges[i].doFinally();
            }
            reduced.reduce(1, ranges);
            assertEquals(state.flowDiagnostics().force(7, 0), reduced.force(7, 0), 3e-15);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "cancel", "error"})
    void managerReuseClearsForceSlotsAfterEveryTerminalOutcome(String outcome) {
        var configuration = config(0.01);
        long password = 17;
        var manager = new FrameManager<StepContext, CfdRangeFrame>(4, password);
        try (var simulation = new SerialSimulation(configuration, lattice)) {
            var state = simulation.state();
            var scratch = new PopulationGrid(configuration);
            var context = new StepContext(
                    state.shape(),
                    state.current(),
                    scratch.buffer(0),
                    1 / configuration.physics().tau(),
                    1,
                    System.nanoTime(),
                    30_000_000_000L,
                    System::nanoTime,
                    state.geometry(),
                    Vector3.ZERO,
                    1,
                    Guards.DEFAULT);
            simulation.step();
            manager.setFactory(new FrameFactory<>(
                    (id, work) -> {
                        var frame = new CfdRangeFrame(id, manager);
                        frame.replace(work, 0, 16, 0, 8, 0, 8);
                        return frame;
                    },
                    (work, frame) -> frame.replace(work, 0, 16, 0, 8, 0, 8)));
            var frame = manager.getOrCreate(context, password);
            double saved = context.current()[0][1023];
            if (outcome.equals("error")) context.current()[0][1023] = Double.NaN;
            try {
                frame.execute();
                if (outcome.equals("cancel")) frame.kill();
                frame.doFinally();
            } catch (SimulationException error) {
                frame.doFinallyWithError(error);
            } finally {
                context.current()[0][1023] = saved;
            }
            if (outcome.equals("success")) assertEquals(state.flowDiagnostics().force(7, 0), frame.force(0, 0), 0);
            else assertThrows(SimulationException.class, () -> frame.force(0, 0));
            var reused = manager.getOrCreate(context, password);
            assertSame(frame, reused);
            assertNull(manager.get(password));
            reused.execute();
            reused.doFinally();
            assertEquals(state.flowDiagnostics().force(7, 0), reused.force(0, 0), 0);
            assertEquals(state.flowDiagnostics().force(42, 0), reused.force(1, 0), 0);
        } finally {
            manager.close();
        }
    }

    @Test
    void failedForceReductionRetainsPublishedPopulationsAndTotals() {
        var base = config(0.01).config();
        var physics = new Physics(
                null,
                base.physics().lattice(),
                null,
                null,
                null,
                null,
                new ForceReference(1e-150, 1e-10, 1, new Vector3(1, 0, 0)));
        var configuration = ConfigLoader.resolve(
                Path.of("overflow-cd.json"),
                new SimulationConfig(1, base.grid(), physics, base.geometry(), base.execution(), null, null),
                100_000_000);
        try (var simulation = new SerialSimulation(configuration, lattice)) {
            var current = simulation.state().current();
            var published = simulation.state().flowDiagnostics();
            var error = assertThrows(SimulationException.class, simulation::step);
            assertTrue(error.getMessage().contains("drag coefficient"));
            assertEquals(0, simulation.state().completedSteps());
            assertSame(current, simulation.state().current());
            assertSame(published, simulation.state().flowDiagnostics());
            assertEquals(0, published.step());
            assertEquals(0, published.force(7, 0), 0);
        }
    }

    @Test
    void curvedVoxelVolumeErrorDecreasesUnderRefinement() {
        double coarse = sphereVolumeError(2), fine = sphereVolumeError(8);
        assertTrue(fine < coarse / 2);
        assertTrue(fine < 0.02);
    }

    private static double sphereVolumeError(int radius) {
        int size = 2 * radius + 4;
        var config = ConfigLoader.resolve(
                Path.of("sphere.json"),
                new SimulationConfig(
                        1,
                        new GridShape(size, size, size),
                        null,
                        new Geometry(
                                null,
                                null,
                                List.of(new Sphere(1, new Vector3(size / 2.0, size / 2.0, size / 2.0), radius)),
                                null),
                        null,
                        null,
                        null),
                100_000_000);
        long solid = config.config().grid().cellCount()
                - GeometryMask.resolve(config).fluidCells();
        return Math.abs(solid / (4.0 / 3 * Math.PI * radius * radius * radius) - 1);
    }

    @Test
    void openSphereDragResolutionAndDomainSensitivity() {
        double coarse = sphereDrag(1, 10), refined = sphereDrag(2, 10), wider = sphereDrag(1, 14);
        System.out.printf("Sphere Cd: coarse=%.9f refined=%.9f wider=%.9f%n", coarse, refined, wider);
        assertTrue(coarse > 0 && refined > 0 && wider > 0);
        assertTrue(Math.abs(refined / coarse - 1) < 0.1, "diffusive refinement at fixed Re and duration");
        assertTrue(wider < coarse, "reduced transverse blockage reduces drag in this fixture");
    }

    private static double sphereDrag(int scale, int width) {
        var shape = new GridShape(18 * scale, width * scale, width * scale);
        double radius = 2.0 * scale, speed = 0.006 / scale;
        var config = ConfigLoader.resolve(
                Path.of("sphere-study.json"),
                new SimulationConfig(
                        1,
                        shape,
                        new Physics(
                                null,
                                new Lattice(0.1, new Vector3(speed, 0, 0), null),
                                null,
                                null,
                                null,
                                null,
                                new ForceReference(speed, Math.PI * radius * radius, 1, new Vector3(1, 0, 0))),
                        new Geometry(
                                OpenBoundariesTest.faces(0, false),
                                null,
                                List.of(new Sphere(
                                        1, new Vector3(6 * scale, width * scale / 2.0, width * scale / 2.0), radius)),
                                null,
                                new OpenBoundary(new Vector3(speed, 0, 0), 1.0, 0.0)),
                        new Execution(400L * scale * scale, null, null, null, 0L),
                        null,
                        null),
                100_000_000);
        try (var simulation = new SerialSimulation(config, lattice)) {
            var state = simulation.run();
            assertEquals(0, state.flowDiagnostics().massBalanceResidual(), 2e-11);
            return state.flowDiagnostics().dragCoefficient(1);
        }
    }
}
