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
                assertEquals(0, backend.framesCreated(), "prepare must not materialize frames");
                reference.run();
                simulation.run();
                for (int q = 0; q < 19; q++) {
                    assertArrayEquals(
                            reference.state().current()[q], simulation.state().current()[q]);
                }
                assertEquals(
                        reference.state().flowDiagnostics().massChange(),
                        simulation.state().flowDiagnostics().massChange());
                assertTrue(
                        backend.framesCreated() < config.config().grid().cellCount() * 3,
                        "completed frames must recycle during the run");
                simulation.reset();
                simulation.run();
                for (int q = 0; q < 19; q++) {
                    assertArrayEquals(
                            reference.state().current()[q], simulation.state().current()[q]);
                }
            }
        }
    }
}
