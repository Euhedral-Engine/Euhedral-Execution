package io.euhedral_execution.benchmarks.cfd.validation;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "OPENLB_HOME", matches = ".+")
class OpenLbIntegrationTest {
    @Test
    void realPinnedReferenceSuiteCachedRepeatAndViscosityPerturbation() throws Exception {
        Path suite = prepare("smoke.json");
        var first = ValidationRunner.run(suite, home(), System.out);
        assertTrue(first.externalNumericsEligible(), first.cases().toString());
        assertFalse(first.backendEquivalenceVerified());
        var repeated = ValidationRunner.run(suite, home(), System.out);
        assertTrue(repeated.externalNumericsEligible(), repeated.cases().toString());
        assertTrue(repeated.cases().stream()
                .allMatch(c -> Boolean.TRUE.equals(c.evidence().get("referenceReused"))));

        var shear = repeated.cases().stream()
                .filter(c -> c.id().equals("shear"))
                .findFirst()
                .orElseThrow();
        Path original = Path.of(shear.artifacts());
        var fixture =
                ValidationSuite.JSON.readValue(original.resolve("fixture.json").toFile(), ValidationSuite.Case.class);
        var mutation =
                ConfigLoader.load(original.resolve("configuration.json"), List.of("physics.lattice.viscosity=0.13"));
        Path mutated = Files.createDirectory(suite.getParent().resolve("viscosity-perturbation"));
        Path config = mutated.resolve("configuration.json");
        Files.writeString(config, ConfigLoader.replayJson(mutation));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = ValidationProcess.run(
                List.of(
                        java,
                        "-Xmx256m",
                        "-cp",
                        System.getProperty("java.class.path"),
                        ValidationWorker.class.getName(),
                        original.resolve("fixture.json").toString(),
                        config.toString(),
                        mutated.toString()),
                mutated,
                mutated.resolve("candidate.log"),
                60000);
        assertTrue(process.completed());
        var a = Snapshot.read(
                mutated.resolve("snapshot-10.tsv"), mutation.config().grid(), 1, 10);
        var b = Snapshot.read(
                Path.of((String) shear.evidence().get("referenceArtifacts")).resolve("snapshot-10.tsv"),
                mutation.config().grid(),
                1,
                10);
        assertFalse(FieldComparison.compare(a, b, fixture.pressureGauge(), fixture.tolerances())
                .passed());
    }

    @Test
    void bothSolversImproveUnderDiffusiveRefinement() throws Exception {
        var report = ValidationRunner.run(prepare("refinement.json"), home(), System.out);
        assertTrue(report.externalNumericsEligible(), report.cases().toString());
        var tree = ValidationSuite.JSON.valueToTree(report);
        for (String name : List.of("shear", "channel")) {
            com.fasterxml.jackson.databind.JsonNode coarse = null, fine = null;
            for (var c : tree.path("cases")) {
                if (c.path("id").asText().equals(name)) coarse = c;
                if (c.path("id").asText().equals(name + "-fine")) fine = c;
            }
            assertNotNull(coarse);
            assertNotNull(fine);
            for (int i = 1; i < 4; i++)
                for (String solver : List.of("candidateAnalytical", "referenceAnalytical")) {
                    var a = coarse.path("evidence").path("samples").get(i);
                    var b = fine.path("evidence").path("samples").get(i);
                    assertEquals(a.path("time").asDouble(), b.path("time").asDouble());
                    assertTrue(b.path(solver).path("rmsError").asDouble()
                            < .4 * a.path(solver).path("rmsError").asDouble());
                }
        }
    }

    @Test
    void differentGridComparisonRetainsCoverageButCannotClaimUnverifiedEligibility() throws Exception {
        var report = ValidationRunner.run(prepare("convergence.json"), home(), System.out);
        assertFalse(report.externalNumericsEligible());
        var result = report.cases().getFirst();
        assertEquals(ValidationSuite.Status.INCOMPATIBLE, result.status(), result.reason());
        var samples = ValidationSuite.JSON.valueToTree(result).path("evidence").path("samples");
        assertEquals(4, samples.size());
        for (var sample : samples)
            assertEquals(1, sample.path("coverage").path("fraction").asDouble());
    }

    private static Path home() {
        return Path.of(System.getenv("OPENLB_HOME"));
    }

    private static Path prepare(String name) throws Exception {
        Path base = Path.of("validation/suites").toAbsolutePath();
        var original = ValidationSuite.load(base.resolve(name));
        var fixtures = new ArrayList<ValidationSuite.Case>();
        for (var c : original.cases())
            fixtures.add(new ValidationSuite.Case(
                    c.id(),
                    base.resolve(c.config()).toString(),
                    c.features(),
                    c.mode(),
                    c.pressureGauge(),
                    c.sampleSteps(),
                    c.tolerances(),
                    c.evidence() == null
                            ? null
                            : new ValidationSuite.Evidence(
                                    base.resolve(c.evidence().file()).toString(),
                                    c.evidence().sha256()),
                    c.correspondence(),
                    c.refinementEvidence(),
                    c.referenceConfig() == null
                            ? null
                            : base.resolve(c.referenceConfig()).toString(),
                    c.sampling(),
                    c.minimumCoverage()));
        Path retained = Files.createTempDirectory(Path.of("build"), "openlb-integration-")
                .toAbsolutePath();
        Path suite = retained.resolve("suite.json");
        Files.writeString(suite, ConfigLoader.json(new ValidationSuite(1, retained.toString(), 60000, fixtures)));
        return suite;
    }
}
