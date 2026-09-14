package io.euhedral_execution.benchmarks.cfd.solver;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.benchmark.PopulationReference;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.execution.*;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Isolated
class SimulationResetTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"serial,3", "euhedral,4", "fjp,3", "static,4"})
    void resetRetainsBothBuffersAndReproducesFullFieldsAndForces(String backend, int steps) throws Exception {
        var config = ConfigLoader.load(
                Path.of("validation/cases/obstacle.json"),
                List.of("execution.steps=" + steps, "execution.brick={\"nx\":3,\"ny\":4,\"nz\":4}"));
        var lattice = CfdTestRuntime.singleWorker();
        try (AutoCloseable owner = lattice::close;
                ExecutionBackend execution =
                        switch (backend) {
                            case "fjp" -> new ForkJoinBackend(new int[2], false, 1000);
                            case "static" -> new StaticBackend(new int[2], false, 1000);
                            default ->
                                new EuhedralBackend(
                                        lattice,
                                        !backend.equals("serial"),
                                        backend.equals("serial") ? 1 : 3,
                                        false,
                                        1000);
                        };
                var simulation = new Simulation(config, GeometryMask.resolve(config, lattice), execution)) {
            var first = simulation.state().current();
            var second = simulation.state().next();
            simulation.reset();
            simulation.run();
            Path reference = directory.resolve("complete.bin");
            PopulationReference.write(reference, simulation.state(), "identity");
            for (int repeat = 0; repeat < 3; repeat++) {
                simulation.reset();
                assertSame(first, simulation.state().current());
                assertSame(second, simulation.state().next());
                assertEquals(0, simulation.state().completedSteps());
                assertEquals(0, simulation.state().flowDiagnostics().step());
                assertEquals(0, simulation.state().flowDiagnostics().massChange());
                simulation.run();
                PopulationReference.verify(reference, simulation.state(), "identity");
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PopulationReference.verify(reference, simulation.state(), "wrong"));
            byte[] data = Files.readAllBytes(reference);
            data[data.length - 1] ^= 1;
            Files.write(reference, data);
            assertThrows(
                    java.io.IOException.class,
                    () -> PopulationReference.verify(reference, simulation.state(), "identity"));
            simulation.reset();
            simulation.state().current()[0][0] = Double.NaN;
            assertThrows(SimulationException.class, simulation::step);
            assertThrows(IllegalStateException.class, simulation::reset);
            simulation.close();
            assertThrows(IllegalStateException.class, simulation::reset);
        }
    }
}
