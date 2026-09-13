package io.euhedral_execution.benchmarks.cfd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
