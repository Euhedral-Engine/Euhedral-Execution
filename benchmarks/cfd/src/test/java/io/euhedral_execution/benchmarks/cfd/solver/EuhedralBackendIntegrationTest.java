package io.euhedral_execution.benchmarks.cfd.solver;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.execution.EuhedralBackend;
import io.euhedral_execution.benchmarks.cfd.execution.ForkJoinBackend;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
@Isolated
class EuhedralBackendIntegrationTest {
    @org.junit.jupiter.api.Test
    void missingPreallocatedFrameFailsWithoutAllocatingOrPublishingTheStep() throws Exception {
        var config = ConfigLoader.load(Path.of("scenes/periodic-smoke.json"));
        var lattice = CfdTestRuntime.singleWorker();
        try (AutoCloseable runtime = lattice::close) {
            var geometry = GeometryMask.resolve(config, lattice);
            try (var backend = new EuhedralBackend(lattice, true, 1, false, 5000);
                    var simulation = new Simulation(config, geometry, backend)) {
                /// Fault injection before publication, while no source can consume its manager.
                var sourcesField = EuhedralBackend.class.getDeclaredField("sources");
                sourcesField.setAccessible(true);
                Object source = ((Object[]) sourcesField.get(backend))[0];
                var managerField = source.getClass().getDeclaredField("manager");
                managerField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var manager = (io.euhedral_execution.core.impl.FrameManager<
                                Object, io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame>)
                        managerField.get(source);
                assertThrows(
                        IllegalStateException.class, () -> manager.getFactory().create(source));
                assertEquals(backend.framesPreallocated(), manager.dump(Long.MAX_VALUE, 0));
                var current = simulation.state().current();
                var error = assertThrows(IllegalStateException.class, simulation::step);
                assertTrue(error.getMessage().contains("preallocated CFD recycler exhausted"));
                assertSame(current, simulation.state().current());
                assertEquals(0, simulation.state().completedSteps());
                assertEquals(0, backend.framesCreatedDuringExecution());
                assertEquals(1, backend.recyclerMisses());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7})
    void lazySingleCellRangesRecycleAcrossGenerationsAndMatchForkJoin(int sources) throws Exception {
        var config = ConfigLoader.load(
                Path.of("scenes/periodic-smoke.json"),
                List.of(
                        "grid.nx=33",
                        "grid.ny=17",
                        "grid.nz=9",
                        "execution.steps=3",
                        "execution.brick.nx=1",
                        "execution.brick.ny=1",
                        "execution.brick.nz=1"));
        var lattice = CfdTestRuntime.upToTwoWorkers();
        try (AutoCloseable runtime = lattice::close) {
            var geometry = GeometryMask.resolve(config, lattice);
            try (var backend = new EuhedralBackend(lattice, true, sources, false, 5000);
                    var simulation = new Simulation(config, geometry, backend);
                    var reference =
                            new Simulation(config, geometry, new ForkJoinBackend(new int[] {0, 1}, false, 5000))) {
                assertEquals(config.config().grid().cellCount(), backend.framesPreallocated());
                assertEquals(0, backend.framesCreatedDuringExecution());
                assertEquals(0, backend.recyclerMisses());
                reference.run();
                simulation.run();
                assertEquals(0, backend.framesCreatedDuringExecution());
                assertEquals(0, backend.recyclerMisses());
                for (int q = 0; q < 19; q++) {
                    assertArrayEquals(
                            reference.state().current()[q], simulation.state().current()[q]);
                }
                assertEquals(
                        reference.state().flowDiagnostics().massChange(),
                        simulation.state().flowDiagnostics().massChange());
                assertEquals(config.config().grid().cellCount(), backend.framesPreallocated());
                assertEquals(0, backend.framesCreatedDuringExecution());
                assertEquals(0, backend.recyclerMisses());
                simulation.reset();
                simulation.run();
                assertEquals(0, backend.framesCreatedDuringExecution());
                assertEquals(0, backend.recyclerMisses());
                for (int q = 0; q < 19; q++) {
                    assertArrayEquals(
                            reference.state().current()[q], simulation.state().current()[q]);
                }
            }
        }
    }
}
