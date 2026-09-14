package io.euhedral_execution.benchmarks.cfd.solver;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.execution.*;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Isolated
class BackendTest {
    @ParameterizedTest
    @CsvSource({
        "periodic-shear,5,4,3",
        "forced-channel,5,4,3",
        "duct-obstacle-smoke,5,4,3",
        "periodic-shear,32,32,32",
        "forced-channel,32,32,32",
        "duct-obstacle-smoke,32,32,32"
    })
    void everyBackendProducesIdenticalPopulationsFieldsAndReductions(String scene, int bx, int by, int bz)
            throws Exception {
        var config = ConfigLoader.load(
                Path.of("scenes/" + scene + ".json"),
                List.of(
                        "execution.steps=4",
                        "grid.nx=17",
                        "grid.ny=9",
                        "grid.nz=7",
                        "execution.diagnosticsEverySteps=1",
                        "execution.brick.nx=" + bx,
                        "execution.brick.ny=" + by,
                        "execution.brick.nz=" + bz));
        var lattice = CfdTestRuntime.upToTwoWorkers();
        try (AutoCloseable runtime = lattice::close) {
            var geometry = GeometryMask.resolve(config, lattice);
            for (int workers : new int[] {1, 2}) {
                for (int variant = 0; variant < 5; variant++) {
                    try (var serial = new SerialSimulation(config, lattice);
                            ExecutionBackend backend =
                                    switch (variant) {
                                        case 0 -> new ForkJoinBackend(new int[workers], false, 2000);
                                        case 1 -> new StaticBackend(new int[workers], false, 2000);
                                        default ->
                                            new EuhedralBackend(
                                                    lattice,
                                                    true,
                                                    variant == 2 ? 1 : variant == 3 ? workers : 5,
                                                    false,
                                                    2000);
                                    };
                            var parallel = new Simulation(config, geometry, backend)) {
                        double[] expected = new double[5], actual = new double[5];
                        for (int step = 1; step <= 4; step++) {
                            serial.step();
                            parallel.step();
                            var a = serial.state();
                            var b = parallel.state();
                            assertEquals(step, b.completedSteps());
                            for (int q = 0; q < 19; q++) {
                                assertArrayEquals(
                                        a.current()[q],
                                        b.current()[q],
                                        0.0,
                                        "backend=" + variant + ", workers=" + workers + ", step=" + step + ", q=" + q);
                            }
                            for (int z = 0; z < 7; z++) {
                                for (int y = 0; y < 9; y++) {
                                    for (int x = 0; x < 17; x++) {
                                        if (geometry.isSolid(x + 17 * (y + 9 * z))) {
                                            continue;
                                        }
                                        a.readField(x, y, z, expected, false);
                                        b.readField(x, y, z, actual, false);
                                        assertArrayEquals(expected, actual, 0.0);
                                    }
                                }
                            }
                            assertEquals(a.diagnostics(), b.diagnostics());
                            var af = a.flowDiagnostics();
                            var bf = b.flowDiagnostics();
                            assertEquals(af.massChange(), bf.massChange());
                            assertEquals(af.inletFlux(), bf.inletFlux());
                            assertEquals(af.outletFlux(), bf.outletFlux());
                            assertEquals(af.macroscopicInletFlux(), bf.macroscopicInletFlux());
                            assertEquals(af.macroscopicOutletFlux(), bf.macroscopicOutletFlux());
                            for (int slot = 0; slot < af.obstacleCount(); slot++) {
                                for (int axis = 0; axis < 3; axis++) {
                                    assertEquals(
                                            af.force(af.obstacleId(slot), axis), bf.force(bf.obstacleId(slot), axis));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"fjp,false", "static,false", "euhedral,false", "fjp,true", "static,true", "euhedral,true"})
    void workerExceptionOrFatalErrorPreservesPublishedState(String kind, boolean fatal) throws Exception {
        var config = ConfigLoader.load(Path.of("scenes/periodic-smoke.json"), List.of("execution.steps=3"));
        var lattice = CfdTestRuntime.singleWorker();
        try (AutoCloseable runtime = lattice::close) {
            Thread driver = Thread.currentThread();
            var injected = new AtomicBoolean();
            try (var backend = backend(kind, lattice);
                    var simulation = new Simulation(config, GeometryMask.resolve(config, lattice), backend, () -> {
                        if (Thread.currentThread() != driver && injected.compareAndSet(false, true)) {
                            if (fatal) {
                                throw new AssertionError("injected worker failure");
                            }
                            throw new IllegalStateException("injected worker failure");
                        }
                        return System.nanoTime();
                    })) {
                var current = simulation.state().current();
                var diagnostics = simulation.state().diagnostics();
                assertThrows(RuntimeException.class, simulation::step);
                assertTrue(injected.get());
                assertSame(current, simulation.state().current());
                assertSame(diagnostics, simulation.state().diagnostics());
                assertEquals(0, simulation.state().completedSteps());
                assertThrows(IllegalStateException.class, simulation::step);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"fjp", "static", "euhedral"})
    void interruptedGenerationWaitsForBodyExitBeforeReturning(String kind) throws Exception {
        var config = ConfigLoader.load(Path.of("scenes/periodic-smoke.json"));
        var lattice = CfdTestRuntime.singleWorker();
        try (AutoCloseable runtime = lattice::close) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var exiting = new CountDownLatch(1);
            var observed = new AtomicReference<Throwable>();
            var owner = new AtomicReference<Thread>();
            var blocked = new AtomicBoolean();
            var returnedQuiescent = new AtomicBoolean();
            var preservedInterrupt = new AtomicBoolean();
            try (var backend = backend(kind, lattice);
                    var simulation = new Simulation(config, GeometryMask.resolve(config, lattice), backend, () -> {
                        if (Thread.currentThread() != owner.get() && blocked.compareAndSet(false, true)) {
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("test gate expired");
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(error);
                            } finally {
                                exiting.countDown();
                            }
                        }
                        return System.nanoTime();
                    })) {
                Thread driver = Thread.ofPlatform().unstarted(() -> {
                    try {
                        simulation.step();
                    } catch (Throwable error) {
                        observed.set(error);
                    }
                    returnedQuiescent.set(exiting.getCount() == 0);
                    preservedInterrupt.set(Thread.currentThread().isInterrupted());
                });
                owner.set(driver);
                driver.start();
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    driver.interrupt();
                    assertEquals(0, simulation.state().completedSteps());
                    release.countDown();
                    driver.join(5000);
                    assertFalse(driver.isAlive());
                    assertInstanceOf(SimulationException.class, observed.get());
                    assertTrue(returnedQuiescent.get());
                    assertTrue(preservedInterrupt.get());
                    assertEquals(0, simulation.state().completedSteps());
                } finally {
                    release.countDown();
                    driver.interrupt();
                    driver.join(5000);
                }
            }
        }
    }

    private static ExecutionBackend backend(
            String kind, io.euhedral_execution.core.control_plane.ControlPlaneLattice lattice) {
        return switch (kind) {
            case "fjp" -> new ForkJoinBackend(new int[2], false, 1000);
            case "static" -> new StaticBackend(new int[2], false, 1000);
            default -> new EuhedralBackend(lattice, true, 2, false, 1000);
        };
    }
}
