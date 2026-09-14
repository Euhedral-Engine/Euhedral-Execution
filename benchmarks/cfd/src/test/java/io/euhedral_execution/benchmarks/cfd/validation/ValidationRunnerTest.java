package io.euhedral_execution.benchmarks.cfd.validation;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidationRunnerTest {
    @TempDir
    Path directory;

    @Test
    void absentInstallationProducesExplicitReportAndNoEligibility() throws Exception {
        var source = ValidationSuite.load(Path.of("validation/suites/smoke.json"));
        var suite = new ValidationSuite(1, directory.resolve("output").toString(), 1000, source.cases());
        Path path = directory.resolve("suite.json");
        Files.writeString(path, ConfigLoader.json(suite));
        var report =
                ValidationRunner.run(path, directory.resolve("absent"), new PrintStream(new ByteArrayOutputStream()));
        assertFalse(report.externalNumericsEligible());
        assertFalse(report.backendEquivalenceVerified());
        assertTrue(report.cases().stream().allMatch(r -> r.status() == ValidationSuite.Status.UNAVAILABLE));
        try (var outputs = Files.walk(directory)) {
            assertEquals(
                    1,
                    outputs.filter(p -> p.getFileName().toString().equals("report.md"))
                            .count());
        }
    }

    @Test
    void changedOrIncompleteReferenceCannotBeReused() throws Exception {
        Files.writeString(directory.resolve("snapshot-0.tsv"), "first");
        Files.writeString(directory.resolve("completed"), "1\n");
        Files.writeString(directory.resolve("manifest.json"), ConfigLoader.json(ValidationRunner.manifest(directory)));
        assertTrue(ValidationRunner.validCache(directory));
        Files.writeString(directory.resolve("snapshot-0.tsv"), "changed");
        assertFalse(ValidationRunner.validCache(directory));
    }

    @Test
    void processSuccessFailureAndDeadlineAreDistinct() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        assertTrue(ValidationProcess.run(List.of(java, "-version"), directory, directory.resolve("success.log"), 10000)
                .completed());
        var failure = ValidationProcess.run(
                List.of(java, "DoesNotExist"), directory, directory.resolve("failure.log"), 10000);
        assertFalse(failure.completed());
        assertFalse(failure.timedOut());
        String classes = Path.of(ValidationRunnerTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        var timeout = ValidationProcess.run(
                List.of(java, "-cp", classes, WaitForever.class.getName()),
                directory,
                directory.resolve("timeout.log"),
                100);
        assertTrue(timeout.timedOut());
    }

    public static class WaitForever {
        public static void main(String[] args) throws InterruptedException {
            /// Intentionally nonterminating child exercises the parent's deadline and kill path.
            new java.util.concurrent.CountDownLatch(1).await();
        }
    }

    @Test
    void limitsNeedFiniteExplicitScalesAndStrictSampleOrdering() {
        assertThrows(IllegalArgumentException.class, () -> new ValidationSuite.Tolerance(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ValidationSuite.Tolerance(Double.NaN, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ValidationSuite.Case(
                        "x",
                        "x",
                        List.of("rest"),
                        ValidationSuite.Mode.MATCHED_DISCRETIZATION,
                        ValidationSuite.Gauge.ABSOLUTE,
                        List.of(0L, 1L, 1L),
                        FieldComparisonTest.LIMITS,
                        null,
                        "case",
                        List.of()));
    }
}
