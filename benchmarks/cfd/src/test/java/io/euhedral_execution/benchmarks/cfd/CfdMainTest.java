package io.euhedral_execution.benchmarks.cfd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class CfdMainTest {
    @TempDir
    Path directory;

    private record Result(int code, String out, String err) {}

    private Result run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = CfdMain.run(args, new PrintStream(out), new PrintStream(err));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpAndArgumentErrors() {
        assertEquals(0, run().code());
        assertTrue(run("--help").out().contains("inspect --config"));
        assertEquals(0, run("inspect", "--help").code());
        assertEquals(2, run("simulate").code());
        assertEquals(2, run("inspect", "--config").code());
        assertEquals(2, run("inspect", "--config", "missing", "--extra").code());
    }

    @Test
    void smokeFixtureInspectionReportsResolvedInputs() {
        var result = run("inspect", "--config", "scenes/periodic-smoke.json");
        assertEquals(0, result.code(), result.err());
        assertEquals("", result.err());
        assertTrue(result.out().contains("Cell count: 960"));
        assertTrue(result.out().contains("Population bytes (exact payload): 291840"));
        assertTrue(result.out().contains("Initial Reynolds: 1.0"));
        assertTrue(result.out().contains("Direction arrays indexable: true"));
        assertTrue(result.out().contains("no populations allocated or workers started"));
    }

    @Test
    void simulateSmokeAndShearWithSerialBackend() {
        assertTrue(run("simulate", "--help").out().contains("--backend serial"));
        var smoke = run("simulate", "--config", "scenes/periodic-smoke.json", "--backend", "serial");
        assertEquals(0, smoke.code(), smoke.err());
        assertTrue(smoke.out().contains("Completed steps: 20"));
        assertTrue(smoke.out().contains("Finite fields and positive density: true"));
        var shear = run("simulate", "--backend", "serial", "--config", "scenes/periodic-shear.json");
        assertEquals(0, shear.code(), shear.err());
        assertTrue(shear.out().contains("Completed steps: 100"));
        assertTrue(
                run("inspect", "--config", "scenes/periodic-shear.json").out().contains("Lattice shear profile"));
        assertEquals(
                2,
                run("simulate", "--config", "scenes/periodic-smoke.json", "--backend", "fjp")
                        .code());
        assertEquals(
                2,
                run("inspect", "--config", "scenes/periodic-smoke.json", "--backend", "serial")
                        .code());
        assertEquals(
                2,
                run("simulate", "--config", "scenes/periodic-smoke.json", "--config", "duplicate")
                        .code());
    }

    @Test
    void simulationRejectsUnsupportedPhysicsAndExportBeforeAllocation() throws Exception {
        Path path = directory.resolve("unsupported.json");
        for (String field : new String[] {
            "\"geometry\":{\"faces\":{\"yMin\":\"WALL\",\"yMax\":\"WALL\"}}",
            "\"physics\":{\"lattice\":{\"acceleration\":{\"x\":1e-6}}}",
            "\"physics\":{\"physical\":{\"voxelWidth\":1,\"timeStep\":1e-100,\"densityReference\":1000,"
                    + "\"viscosity\":1e99,\"acceleration\":{\"x\":1e-200}}}",
            "\"output\":{\"exportEverySteps\":1}"
        }) {
            /// This inspectable grid would require 304 GB if the capability check allocated first.
            Files.writeString(
                    path,
                    "{\"schemaVersion\":1,\"grid\":{\"nx\":1000,\"ny\":1000,\"nz\":1000},"
                            + "\"memoryLimitBytes\":400000000000," + field + "}");
            var result = run("simulate", "--config", path.toString());
            assertEquals(2, result.code(), result.err());
            assertFalse(Files.exists(directory.resolve("output")));
        }
    }

    @Test
    void numericalFailureHasDistinctExitStatusAndInitializationContext() throws Exception {
        Path path = directory.resolve("nonfinite.json");
        Files.writeString(path, """
            {"schemaVersion":1,"grid":{"nx":3,"ny":4,"nz":5},"physics":{"densityReference":1e308}}
            """);
        var result = run("simulate", "--config", path.toString());
        assertEquals(3, result.code(), result.err());
        assertTrue(result.err().contains("step=0 cell=("));
        assertFalse(Files.exists(directory.resolve("output")));
    }

    @Test
    void missingInvalidAndOverBudgetConfigsReturnDiagnostics() throws Exception {
        assertEquals(
                2,
                run("inspect", "--config", directory.resolve("missing.json").toString())
                        .code());
        Path path = directory.resolve("bad.json");
        Files.writeString(path, "{\"schemaVersion\":1}");
        assertTrue(run("inspect", "--config", path.toString()).err().contains("grid is required"));
        Files.writeString(path, """
            {"schemaVersion":1,"grid":{"nx":100,"ny":101,"nz":102},"memoryLimitBytes":1}
            """);
        var result = run("inspect", "--config", path.toString());
        assertEquals(2, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("100x101x102"));
        assertTrue(result.err().contains("memory budget exceeded"));
        assertFalse(Files.exists(directory.resolve("output")));
    }
}
