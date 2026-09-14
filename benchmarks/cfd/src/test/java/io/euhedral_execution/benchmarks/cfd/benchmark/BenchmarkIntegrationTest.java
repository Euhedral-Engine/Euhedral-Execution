package io.euhedral_execution.benchmarks.cfd.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "OPENLB_HOME", matches = ".+")
/// JMH owns a process-wide benchmark lock; parameterized campaigns must not overlap.
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class BenchmarkIntegrationTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS)
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realJmhForksEveryBackendAndRetainsFullFieldChecksAndIneligibleDuct(boolean scaled) throws Exception {
        ObjectNode suite = (ObjectNode)
                BenchmarkSuite.JSON.readTree(Path.of("suites/smoke.json").toFile());
        suite.put(
                "validationSuite",
                Path.of("validation/suites/coverage.json").toAbsolutePath().toString());
        suite.put("outputDirectory", directory.resolve("results").toString());
        /// Keep correctness-test JMH locks separate from a user's running benchmark.
        ((com.fasterxml.jackson.databind.node.ArrayNode) suite.get("jvmArgs")).add("-Djava.io.tmpdir=" + directory);
        suite.put("forks", 2)
                .put("workers", 2)
                .put("warmupIterations", 1)
                .put("measurementIterations", 1)
                .put("maxForkCv", 2)
                .put("iterationMillis", 20)
                .put("preSteps", 99)
                .put("stepsPerInvocation", 1);
        Path periodic = Path.of("validation/cases/shear.json").toAbsolutePath();
        if (scaled) {
            ObjectNode physical = (ObjectNode) BenchmarkSuite.JSON.readTree(periodic.toFile());
            physical.putObject("grid").put("nx", 16).put("ny", 20).put("nz", 24);
            ((ObjectNode) physical.get("execution")).put("steps", 120);
            periodic = directory.resolve("scaled-periodic.json");
            Files.writeString(periodic, physical.toString());
            suite.put("preSteps", 119).put("referenceBackend", "fjp");
        }
        var cases = suite.putArray("cases");
        cases.addObject()
                .put("id", "periodic")
                .put("config", periodic.toString())
                .put("validationCase", "shear")
                .put("validationScope", scaled ? "PERIODIC_SHEAR_FAMILY" : "EXACT");
        cases.addObject()
                .put("id", "duct")
                .put(
                        "config",
                        Path.of("validation/cases/duct.json").toAbsolutePath().toString())
                .put("validationCase", "duct");
        Path file = directory.resolve("suite.json");
        Files.writeString(file, suite.toString());
        var terminal = new java.io.ByteArrayOutputStream();
        var report = BenchmarkRunner.run(
                file, new java.io.PrintStream(terminal, true, java.nio.charset.StandardCharsets.UTF_8));
        String progress = terminal.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(progress.contains("[CFD progress]"), progress);
        assertTrue(progress.contains("full reference | complete | step " + (scaled ? "120/120" : "100/100")), progress);
        assertTrue(progress.contains("measurement 1/1 invocation 1 | complete | step 1/1"), progress);
        assertTrue(progress.contains("ETA 00:00:00"), progress);
        assertFalse(progress.contains("backend preflight"), progress);
        assertFalse(progress.contains("bounded serial"), progress);
        assertEquals(2, report.cases().size());
        var periodicResult = report.cases().getFirst();
        assertEquals("ELIGIBLE", periodicResult.externalStatus(), periodicResult.reason());
        assertEquals(
                java.util.List.of("euhedral-workers", "fjp", "static"),
                periodicResult.variants().stream()
                        .map(BenchmarkResults.Summary::id)
                        .toList());
        var reference = BenchmarkSuite.JSON.readTree(Path.of(periodicResult.directory())
                .resolve("reference/reference.json")
                .toFile());
        assertEquals("fjp", reference.path("backend").asText());
        assertFalse(reference.has("qualification"));
        assertFalse(Files.exists(Path.of(periodicResult.directory()).resolve("reference/qualification")));
        assertFalse(Files.exists(Path.of(periodicResult.directory()).resolve("serial")));
        var pids = new HashSet<Long>();
        for (var variant : periodicResult.variants()) {
            assertEquals("PASSED", variant.status(), variant.forks().toString());
            assertEquals(2, variant.forks().size());
            assertNotNull(variant.speedup());
            assertNull(variant.parallelEfficiency());
            for (var fork : variant.forks()) {
                assertEquals("COMPLETED", fork.status(), fork.reason());
                assertTrue(fork.secondsPerInvocation() > 0);
                assertEquals(1, fork.iterations().size());
                Path path = Path.of(fork.directory());
                var trial =
                        BenchmarkSuite.JSON.readTree(path.resolve("trial.json").toFile());
                assertTrue(pids.add(trial.path("pid").asLong()));
                assertTrue(trial.path("backendEquivalenceVerified").asBoolean());
                assertTrue(trial.path("checkedInvocations").asLong() >= 2);
                assertTrue(trial.path("checkedWarmupInvocations").asLong() > 0);
                var configuration = io.euhedral_execution.benchmarks.cfd.config.ConfigLoader.load(
                        Path.of(trial.path("job").path("configuration").asText()));
                assertEquals(0, configuration.config().execution().diagnosticsEverySteps());
                assertEquals(
                        "CHECKED_WARMUP",
                        trial.path("backendQualificationPolicy").asText());
                Path snapshot = path.resolve("simulation-final.vti");
                if (variant.backend().equals("euhedral")) {
                    assertTrue(Files.isRegularFile(snapshot));
                    assertEquals(
                            snapshot.toString(), trial.path("visualizationFile").asText());
                    assertTrue(trial.path("visualizationExportNs").asLong() > 0);
                    try (var input = Files.newInputStream(snapshot)) {
                        String header = new String(input.readNBytes(2048), java.nio.charset.StandardCharsets.US_ASCII);
                        assertTrue(header.contains("Name=\"velocity\""), header);
                        assertTrue(header.contains("Name=\"TimeValue\""), header);
                        assertTrue(header.contains(">" + (scaled ? "120.0" : "100.0") + "</DataArray>"), header);
                    }
                } else {
                    assertFalse(Files.exists(snapshot));
                    assertFalse(trial.hasNonNull("visualizationFile"));
                }
                assertTrue(trial.path("resetAndPreStepsNs").asLong() > 0);
                assertTrue(trial.path("fullFieldComparisonNs").asLong() > 0);
                assertEquals("DRIVER", trial.path("firstTouchPolicy").asText());
                assertEquals("fjp", trial.path("referenceBackend").asText());
                assertEquals(
                        scaled ? "PERIODIC_SHEAR_FAMILY" : "EXACT",
                        trial.path("externalCoverage").asText());
                assertTrue(Files.isRegularFile(path.resolve("jmh.json")));
                assertTrue(Files.isRegularFile(path.resolve("command.json")));
            }
        }
        assertEquals("INELIGIBLE", report.cases().get(1).externalStatus());
        assertTrue(report.cases().get(1).variants().isEmpty());
        var output = Path.of(report.directory());
        assertTrue(Files.isRegularFile(output.resolve("external-validation.json")));
        assertTrue(Files.isRegularFile(output.resolve("comparison.csv")));
        assertTrue(Files.isRegularFile(output.resolve("comparison.md")));
        assertTrue(Files.readString(output.resolve("comparison.csv"))
                .contains("periodic," + (scaled ? "PERIODIC_SHEAR_FAMILY" : "EXACT") + ","));
    }
}
