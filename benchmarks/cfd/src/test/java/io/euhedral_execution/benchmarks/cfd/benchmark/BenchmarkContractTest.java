package io.euhedral_execution.benchmarks.cfd.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.validation.NumericalIdentity;
import io.euhedral_execution.benchmarks.cfd.validation.ValidationRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BenchmarkContractTest {
    @TempDir
    Path directory;

    @Test
    void presetsUseWorkerCountSourcesAndExplicitMeasurementDimensions() throws Exception {
        for (String name : List.of("smoke", "normal")) {
            var suite = BenchmarkSuite.load(Path.of("suites/" + name + ".json"));
            assertEquals("fjp", suite.baselineVariant());
            assertEquals(
                    "workers",
                    suite.variants().stream()
                            .filter(v -> v.id().equals("euhedral-workers"))
                            .findFirst()
                            .orElseThrow()
                            .sources());
            assertEquals(name.equals("smoke") ? 4 : 1, suite.cases().size());
            assertEquals(2, suite.forks());
            assertEquals("fjp", suite.referenceBackend());
            assertEquals(
                    List.of("euhedral-workers", "fjp", "static"),
                    suite.variants().stream().map(BenchmarkSuite.Variant::id).toList());
            assertEquals(name.equals("smoke") ? 100 : 1000, suite.preSteps() + suite.stepsPerInvocation());
        }
    }

    @Test
    void stockWorkloadIsLargeWithoutAllocatingItsPopulationGrid() throws Exception {
        var suite = BenchmarkSuite.load(Path.of("suites/normal.json"));
        var config = ConfigLoader.load(
                Path.of("suites").resolve(suite.cases().getFirst().config()));
        assertEquals(
                new io.euhedral_execution.benchmarks.cfd.config.GridShape(256, 256, 256),
                config.config().grid());
        assertEquals(16_777_216, config.config().grid().cellCount());
        assertEquals(0, suite.preSteps());
        assertEquals(1_000, suite.stepsPerInvocation());
        assertEquals(16_777_216_000L, config.config().grid().cellCount() * suite.stepsPerInvocation());
        assertEquals(suite.stepsPerInvocation(), config.steps());
        assertEquals(0, config.config().execution().diagnosticsEverySteps());
        assertEquals(suite.brick(), config.config().execution().brick());
        assertEquals(new io.euhedral_execution.benchmarks.cfd.config.GridShape(8, 8, 8), suite.brick());
        for (var variant : suite.variants()) {
            assertNull(variant.brick());
            var brick = variant.brickOrDefault(suite.brick());
            assertEquals(suite.brick(), brick);
            assertEquals(32_768, config.config().grid().brickCount(brick));
        }
        assertTrue(config.memory().populationBytes() > 5_000_000_000L);
        assertTrue(config.memory().totalBytes() < config.memory().budgetBytes());
        assertTrue(suite.jvmArgs().contains("-Xmx12g"));
        assertEquals("fjp", suite.referenceBackend());
        assertEquals(
                List.of("euhedral-workers", "fjp", "static"),
                suite.variants().stream().map(BenchmarkSuite.Variant::id).toList());
        assertNull(suite.workers());
        assertTrue(suite.processDeadlineMillis() > 86_400_000);
        assertEquals(
                BenchmarkSuite.ValidationScope.PERIODIC_SHEAR_FAMILY,
                suite.cases().getFirst().validationScope());
    }

    @Test
    void variantBricksAreOptionalButMustHavePositiveDimensions() throws Exception {
        var fallback = new io.euhedral_execution.benchmarks.cfd.config.GridShape(8, 8, 8);
        var inherited =
                BenchmarkSuite.JSON.readValue("{\"id\":\"fjp\",\"backend\":\"fjp\"}", BenchmarkSuite.Variant.class);
        assertEquals(fallback, inherited.brickOrDefault(fallback));
        assertThrows(
                Exception.class,
                () -> BenchmarkSuite.JSON.readValue(
                        "{\"id\":\"fjp\",\"backend\":\"fjp\",\"brick\":{\"nx\":0,\"ny\":8,\"nz\":8}}",
                        BenchmarkSuite.Variant.class));
    }

    @Test
    void diagnosticCadenceIsExecutionMetadataAndDoesNotChangePhysicalEvidence() throws Exception {
        var file = Path.of("validation/cases/obstacle.json");
        var observed = ConfigLoader.load(file);
        var finalOnly = ConfigLoader.load(file, List.of("execution.diagnosticsEverySteps=0"));
        assertEquals(NumericalIdentity.caseIdentity(observed), NumericalIdentity.caseIdentity(finalOnly));
        assertNull(ValidationGate.rejection(evidence(observed), "obstacle", finalOnly, "numerical"));
    }

    @Test
    void representativeShearCoverageAllowsOnlyExplicitGridAndDurationScaling() throws Exception {
        var small = ConfigLoader.load(Path.of("validation/cases/shear.json"));
        var large = ConfigLoader.load(Path.of("suites/cases/periodic-256.json"));
        var report = evidence(small);
        var result = (ObjectNode) report.path("cases").get(0);
        result.putArray("features").add("periodic-streaming").add("viscosity").add("transient");
        var details = (ObjectNode) result.path("evidence");
        details.put("periodicShearIdentity", NumericalIdentity.periodicShearIdentity(small));
        var scope = BenchmarkSuite.ValidationScope.PERIODIC_SHEAR_FAMILY;
        assertNotEquals(NumericalIdentity.caseIdentity(small), NumericalIdentity.caseIdentity(large));
        assertNotNull(ValidationGate.rejection(report, "obstacle", large, "numerical"));
        assertNull(ValidationGate.rejection(report, "obstacle", large, "numerical", scope));
        assertNotNull(ValidationGate.rejection(report, "obstacle", large, "stale", scope));
        for (String override : List.of(
                "physics.lattice.viscosity=0.12",
                "physics.shear.amplitude=0.02",
                "physics.shear.modeY=2",
                "physics.lattice.acceleration.x=0.000001",
                "geometry.boxes=[{\"id\":1,\"min\":{\"x\":1,\"y\":1,\"z\":1},\"max\":{\"x\":2,\"y\":2,\"z\":2}}]")) {
            var changed = ConfigLoader.load(Path.of("suites/cases/periodic-256.json"), List.of(override));
            assertNotNull(ValidationGate.rejection(report, "obstacle", changed, "numerical", scope), override);
        }
        var walls = ConfigLoader.load(
                Path.of("suites/cases/periodic-256.json"),
                List.of("geometry.faces.yMin=\"WALL\"", "geometry.faces.yMax=\"WALL\""));
        assertNotNull(ValidationGate.rejection(report, "obstacle", walls, "numerical", scope));
        result.put("verified", false);
        assertNotNull(ValidationGate.rejection(report, "obstacle", large, "numerical", scope));
        result.put("verified", true);
        details.remove("periodicShearIdentity");
        assertNotNull(ValidationGate.rejection(report, "obstacle", large, "numerical", scope));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "stepsPerInvocation",
                "forks",
                "warmupIterations",
                "measurementIterations",
                "iterationMillis",
                "processDeadlineMillis",
                "maxForkCv"
            })
    void zeroWorkOrMeasurementSettingsAreRejected(String field) throws Exception {
        var tree = (ObjectNode)
                BenchmarkSuite.JSON.readTree(Path.of("suites/smoke.json").toFile());
        tree.put(field, 0);
        assertThrows(Exception.class, () -> BenchmarkSuite.JSON.treeToValue(tree, BenchmarkSuite.class));
    }

    @Test
    void metricsUseFluidCellsStepsAndIndependentForkMeans() {
        assertEquals(2, BenchmarkResults.mlups(200_000, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkResults.mlups(0, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkResults.mlups(10, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkResults.mlups(10, 10, Double.NaN));
        var variant = new BenchmarkSuite.Variant("fjp", "fjp", null);
        var forks = List.of(fork(2), fork(4));
        var summary = BenchmarkResults.summarize(variant, 2, 0, forks, 2, 10, 1);
        assertEquals("PASSED", summary.status());
        assertEquals(3, summary.meanSeconds());
        assertEquals(Math.sqrt(2), summary.forkStdDevSeconds());
        assertEquals(Math.sqrt(2) / 3, summary.forkCv());
        assertEquals(2.0 / 3, summary.mlups());
        var serial = BenchmarkResults.summarize(
                new BenchmarkSuite.Variant("serial", "serial", 1), 1, 1, List.of(fork(6), fork(6)), 2, 10, 1);
        var compared = BenchmarkResults.compare(summary, serial, serial);
        assertEquals(2, compared.speedup());
        assertEquals(1, compared.parallelEfficiency());
        var unstable = BenchmarkResults.summarize(variant, 2, 0, forks, 2, 10, .01);
        assertEquals("UNSTABLE", unstable.status());
        assertNull(unstable.mlups());
        assertNull(BenchmarkResults.compare(unstable, serial, serial).speedup());
        var single = BenchmarkResults.summarize(variant, 2, 0, List.of(fork(2)), 1, 10, 1);
        assertEquals("INSUFFICIENT_FORKS", single.status());
        assertNull(single.forkCv());
        assertNull(single.mlups());
        assertEquals(
                "FAILED",
                BenchmarkResults.summarize(variant, 2, 0, List.of(fork(2)), 2, 10, 1)
                        .status());
        var timeout = new BenchmarkResults.Fork("TIMED_OUT", "timeout", "retained", null, List.of(), 0, 1);
        var failed = BenchmarkResults.summarize(variant, 2, 0, List.of(fork(2), timeout), 2, 10, 1);
        assertEquals("TIMED_OUT", failed.status());
        assertNull(failed.meanSeconds());
        assertEquals(2, failed.forks().size());
    }

    private static BenchmarkResults.Fork fork(double seconds) {
        return new BenchmarkResults.Fork("COMPLETED", "checked", "retained", seconds, List.of(seconds), 200_000, 1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "physics.lattice.viscosity=0.12",
                "execution.steps=101",
                "geometry.faces.yMin=\"WALL\"",
                "grid.nx=16",
                "physics.lattice.initialVelocity.x=0.01"
            })
    void physicalChangesCannotReuseExternalEvidence(String override) throws Exception {
        var config = ConfigLoader.load(Path.of("validation/cases/obstacle.json"));
        var report = evidence(config);
        var changed = ConfigLoader.load(
                Path.of("validation/cases/obstacle.json"),
                override.startsWith("geometry.faces")
                        ? List.of(override, "geometry.faces.yMax=\"WALL\"")
                        : List.of(override));
        assertNull(ValidationGate.rejection(report, "obstacle", config, "numerical"));
        assertNotNull(ValidationGate.rejection(report, "obstacle", changed, "numerical"));
    }

    @Test
    void schedulingChangesKeepPhysicalIdentityButStaleUnverifiedAndMissingEvidenceFail() throws Exception {
        var config = ConfigLoader.load(Path.of("validation/cases/obstacle.json"));
        var report = evidence(config);
        var scheduling = ConfigLoader.load(
                Path.of("validation/cases/obstacle.json"),
                List.of(
                        "execution.brick={\"nx\":3,\"ny\":4,\"nz\":4}",
                        "execution.backendOptions.backend=\"euhedral\"",
                        "execution.backendOptions.sources=3"));
        assertEquals(NumericalIdentity.caseIdentity(config), NumericalIdentity.caseIdentity(scheduling));
        assertNull(ValidationGate.rejection(report, "obstacle", scheduling, "numerical"));
        assertNotNull(ValidationGate.rejection(report, "obstacle", scheduling, "changed"));
        assertNotNull(ValidationGate.rejection(report, "missing", config, "numerical"));
        for (String status : List.of("FAILED", "INCOMPATIBLE", "UNAVAILABLE")) {
            ((ObjectNode) report.path("cases").get(0)).put("status", status);
            assertNotNull(ValidationGate.rejection(report, "obstacle", config, "numerical"));
        }
        ((ObjectNode) report.path("cases").get(0)).put("status", "PASSED").put("verified", false);
        assertNotNull(ValidationGate.rejection(report, "obstacle", config, "numerical"));
        assertNotNull(
                ValidationGate.rejection(BenchmarkSuite.JSON.createObjectNode(), "obstacle", config, "numerical"));
    }

    @Test
    void missingValidationRetainsIneligibleCasesWithoutStartingBenchmarkProcesses() throws Exception {
        ObjectNode suite = (ObjectNode)
                BenchmarkSuite.JSON.readTree(Path.of("suites/smoke.json").toFile());
        suite.remove("validationSuite");
        suite.put("validationReport", directory.resolve("absent.json").toString());
        suite.put("outputDirectory", directory.resolve("results").toString());
        for (var fixture : suite.path("cases")) {
            ((ObjectNode) fixture)
                    .put(
                            "config",
                            Path.of("suites")
                                    .resolve(fixture.path("config").asText())
                                    .toAbsolutePath()
                                    .normalize()
                                    .toString());
        }
        Path file = directory.resolve("suite.json");
        Files.writeString(file, suite.toString());
        var report = BenchmarkRunner.run(file, System.out);
        assertEquals(4, report.cases().size());
        assertTrue(report.cases().stream()
                .allMatch(c ->
                        c.externalStatus().equals("INELIGIBLE") && c.variants().isEmpty()));
        try (var paths = Files.walk(Path.of(report.directory()))) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().equals("process.log")));
        }
        assertTrue(Files.readString(Path.of(report.directory()).resolve("comparison.csv"))
                .contains("INELIGIBLE"));
    }

    static ObjectNode evidence(io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration config) throws Exception {
        ObjectNode report = BenchmarkSuite.JSON.createObjectNode();
        report.put("schemaVersion", 1).put("numericalIdentity", "numerical");
        report.putObject("referenceIdentity")
                .put("version", "1.9.0")
                .put("archiveSha256", ValidationRunner.OPENLB_ARCHIVE)
                .put("precision", "double");
        var result = report.putArray("cases")
                .addObject()
                .put("id", "obstacle")
                .put("status", "PASSED")
                .put("verified", true);
        result.putArray("features").add("primitive-obstacles");
        var evidence = result.putObject("evidence")
                .put("caseIdentity", NumericalIdentity.caseIdentity(config))
                .put("toleranceVerification", "frozen evidence matches declared limits");
        evidence.putArray("samples").addObject().put("step", 0);
        evidence.withArray("samples").addObject().put("step", 100);
        return report;
    }
}
