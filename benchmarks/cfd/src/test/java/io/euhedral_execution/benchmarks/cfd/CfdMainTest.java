package io.euhedral_execution.benchmarks.cfd;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
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
        if (args.length > 0
                && args[0].equals("simulate")
                && !(args.length == 2 && args[1].equals("--help"))
                && !java.util.Arrays.asList(args).contains("--output")) {
            var expanded = new java.util.ArrayList<>(java.util.List.of(args));
            expanded.add("--output");
            expanded.add(directory.resolve("runs").toString());
            args = expanded.toArray(String[]::new);
        }
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        /// Simulations resolve their own worker budget; bound the separate voxel-inspection path here.
        var runtime = java.util.Arrays.asList(args).contains("--voxelize") ? CfdTestRuntime.singleWorker() : null;
        try {
            int code = CfdMain.run(args, new PrintStream(out), new PrintStream(err));
            return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            if (runtime != null) runtime.close();
        }
    }

    @Test
    void parallelCliReportsResolvedBudgetsAndReplayableSettings() throws Exception {
        for (String backend : new String[] {"euhedral", "fjp", "static"}) {
            Path output = directory.resolve(backend);
            var result = run(
                    "simulate",
                    "--config",
                    "scenes/periodic-smoke.json",
                    "--backend",
                    backend,
                    "--workers",
                    "1",
                    "--steps",
                    "2",
                    "--output",
                    output.toString());
            assertEquals(0, result.code(), result.err());
            assertTrue(result.out().contains("backend=" + backend));
            Path folder;
            try (var paths = Files.list(output)) {
                folder = paths.findFirst().orElseThrow();
            }
            String resolved = Files.readString(folder.resolve("resolved.json"));
            assertTrue(resolved.contains("\"backend\" : \"" + backend + "\""));
            assertTrue(resolved.contains("\"physicalWorkerCount\" : 1"));
            assertTrue(resolved.contains("\"effectiveCpus\""));
            assertTrue(resolved.contains("\"affinityCapability\""));
            assertTrue(resolved.contains("\"resolvedSources\" : " + (backend.equals("euhedral") ? 1 : 0)));
            var replay =
                    io.euhedral_execution.benchmarks.cfd.config.ConfigLoader.load(folder.resolve("configuration.json"));
            assertEquals(backend, replay.config().execution().backendOptions().backend());
            assertEquals(1, replay.config().execution().backendOptions().workers());
            assertEquals(
                    0,
                    run(
                                    "simulate",
                                    "--config",
                                    folder.resolve("configuration.json").toString())
                            .code());
        }
        var result = run(
                "simulate",
                "--config",
                "scenes/periodic-smoke.json",
                "--backend",
                "euhedral",
                "--workers",
                "1",
                "--sources",
                "3",
                "--steps",
                "2");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("requested=3, resolved=3"));
    }

    @Test
    void importsStlThroughInspectionSimulationForcesAndFieldExport() throws Exception {
        var inspected = run("inspect", "--config", "scenes/stl-obstacle.json", "--voxelize");
        assertEquals(0, inspected.code(), inspected.err());
        assertTrue(inspected.out().contains("Voxelized fluid cells:"));
        assertTrue(inspected.out().contains("cad-box"));
        assertTrue(inspected.out().contains("throughComponents"));
        assertFalse(Files.exists(directory.resolve("runs")));
        var simulated = run("simulate", "--config", "scenes/stl-obstacle.json", "--steps", "3", "--export-every", "2");
        assertEquals(0, simulated.code(), simulated.err());
        assertTrue(simulated.out().contains("Obstacle 1 force"));
        Path folder;
        try (var entries = Files.list(directory.resolve("runs"))) {
            folder = entries.findFirst().orElseThrow();
        }
        assertTrue(Files.readString(folder.resolve("geometry.json")).contains("cad-box"));
        assertTrue(Files.isRegularFile(folder.resolve("frame-000000000003.vti")));
        var replay =
                io.euhedral_execution.benchmarks.cfd.config.ConfigLoader.load(folder.resolve("configuration.json"));
        assertTrue(
                Path.of(replay.config().geometry().meshes().getFirst().file()).isAbsolute());
        assertEquals(12, replay.meshes().getFirst().triangles());
        assertEquals(
                0,
                run(
                                "inspect",
                                "--voxelize",
                                "--config",
                                folder.resolve("configuration.json").toString())
                        .code());
    }

    @Test
    void openSceneSmokeReportsResolvedBoundariesFluxAndDrag() {
        for (String scene : new String[] {"duct-obstacle-smoke", "sphere-wake-smoke"}) {
            var inspection = run("inspect", "--config", "scenes/" + scene + ".json");
            assertEquals(0, inspection.code(), inspection.err());
            assertTrue(inspection.out().contains("Inlet linear ramp duration (lattice steps): 20"));
            assertTrue(inspection.out().contains("Drag reference"));
            var result = run("simulate", "--config", "scenes/" + scene + ".json");
            assertEquals(0, result.code(), result.err());
            assertTrue(result.out().contains("Flow diagnostics step: 100"));
            assertTrue(result.out().contains("Boundary-update inlet flux"));
            assertTrue(result.out().contains("Estimated macroscopic outlet flux"));
            assertTrue(result.out().contains("Obstacle 1 Cd:"));
        }
    }

    @Test
    void exportsTimeSeriesAndMetricsIntoDistinctRunDirectories() throws Exception {
        for (int run = 0; run < 2; run++) {
            var result = run(
                    "simulate",
                    "--config",
                    "scenes/periodic-smoke.json",
                    "--steps",
                    "3",
                    "--export-every",
                    "2",
                    "--format",
                    "ascii",
                    "--set",
                    "execution.diagnosticsEverySteps=2");
            assertEquals(0, result.code(), result.err());
        }
        java.util.List<Path> runs;
        try (var entries = Files.list(directory.resolve("runs"))) {
            runs = entries.toList();
        }
        assertEquals(2, runs.size());
        for (var run : runs) {
            String pvd = Files.readString(run.resolve("flow.pvd"));
            assertTrue(pvd.contains("time_unit=lattice_steps"));
            for (long step : new long[] {0, 2, 3})
                assertTrue(Files.isRegularFile(run.resolve(String.format("frame-%012d.vti", step))));
            assertFalse(Files.exists(run.resolve("frame-000000000001.vti")));
            var metrics = Files.readAllLines(run.resolve("metrics.csv"));
            assertEquals(5, metrics.size());
            assertEquals(metrics.get(0).split(",", -1).length, metrics.get(2).split(",", -1).length);
            assertEquals("", metrics.get(2).split(",", -1)[4], "stale diagnostics must not be labeled as current");
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            assertEquals(
                    "COMPLETED",
                    json.readTree(run.resolve("status.json").toFile())
                            .get("status")
                            .asText());
            var replay =
                    io.euhedral_execution.benchmarks.cfd.config.ConfigLoader.load(run.resolve("configuration.json"));
            assertEquals(3, replay.steps());
            assertEquals(2, replay.config().output().exportEverySteps());
        }
    }

    @Test
    void overridesRejectInvalidOrConflictingValuesBeforeCreatingOutput() {
        for (String override : new String[] {
            "grid.typo=5",
            "physics.lattice.viscosity=-1",
            "grid.nx=2.5",
            "grid.nx=null",
            "output.format=42",
            "output.format=\"bad\""
        })
            assertEquals(
                    2,
                    run("simulate", "--config", "scenes/periodic-smoke.json", "--set", override)
                            .code(),
                    override);
        assertEquals(
                2,
                run("simulate", "--config", "scenes/periodic-smoke.json", "--steps", "1", "--duration", "1")
                        .code());
        assertEquals(
                2,
                run("simulate", "--config", "scenes/periodic-smoke.json", "--steps", "1", "--set", "execution.steps=2")
                        .code());
        assertFalse(Files.exists(directory.resolve("runs")));
    }

    @Test
    void physicalDurationOverrideReplacesConfiguredStepsAndLabelsPvdTime() throws Exception {
        Path file = directory.resolve("physical.json");
        Files.writeString(file, """
                {"schemaVersion":1,"grid":{"nx":3,"ny":4,"nz":5},"physics":{"physical":{
                "voxelWidth":0.02,"timeStep":0.004,"densityReference":1000,"viscosity":0.01}},"execution":{"steps":99}}
                """);
        var result = run("simulate", "--config", file.toString(), "--duration", "0.012", "--export-every", "2");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("Completed steps: 3"));
        Path run;
        try (var files = Files.list(directory.resolve("runs"))) {
            run = files.findFirst().orElseThrow();
        }
        String pvd = Files.readString(run.resolve("flow.pvd"));
        assertTrue(pvd.contains("time_unit=seconds"));
        assertTrue(pvd.contains("timestep=\"0.012\""));
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
                run("simulate", "--config", "scenes/periodic-smoke.json", "--backend", "unknown")
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
    void simulationRejectsUnsupportedFormatBeforeAllocation() throws Exception {
        Path path = directory.resolve("unsupported.json");
        for (String field : new String[] {"\"output\":{\"exportEverySteps\":1}"}) {
            /// This inspectable grid would require 304 GB if the capability check allocated first.
            Files.writeString(
                    path,
                    "{\"schemaVersion\":1,\"grid\":{\"nx\":1000,\"ny\":1000,\"nz\":1000},"
                            + "\"memoryLimitBytes\":400000000000," + field + "}");
            var result = run("simulate", "--config", path.toString(), "--format", "compressed");
            assertEquals(2, result.code(), result.err());
            assertFalse(Files.exists(directory.resolve("output")));
        }
    }

    @Test
    void phaseThreeScenesRunThroughTheCli() {
        for (String scene : new String[] {"forced-channel.json", "periodic-obstacle.json"}) {
            var result = run("simulate", "--config", "scenes/" + scene, "--backend", "serial");
            assertEquals(0, result.code(), result.err());
            assertTrue(result.out().contains("Fluid cells:"));
            assertTrue(result.out().contains("Finite fields and positive density: true"));
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
