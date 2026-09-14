package io.euhedral_execution.benchmarks.cfd.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.validation.NumericalIdentity;
import io.euhedral_execution.benchmarks.cfd.validation.ValidationRunner;
import java.io.IOException;
import java.nio.file.Path;

/// Exact-case and explicitly selected representative coverage stay distinct from full backend equivalence.
public final class ValidationGate {
    private ValidationGate() {}

    public static String rejection(JsonNode report, String caseId, CfdConfiguration config, String numericalIdentity)
            throws IOException {
        return rejection(report, caseId, config, numericalIdentity, BenchmarkSuite.ValidationScope.EXACT);
    }

    public static String rejection(
            JsonNode report,
            String caseId,
            CfdConfiguration config,
            String numericalIdentity,
            BenchmarkSuite.ValidationScope scope)
            throws IOException {
        if (report.path("schemaVersion").asInt() != 1
                || !report.path("numericalIdentity").asText().equals(numericalIdentity)) {
            return "missing or stale numerical validation identity; rerun validate";
        }
        var reference = report.path("referenceIdentity");
        if (!reference.path("version").asText().equals("1.9.0")
                || !reference.path("archiveSha256").asText().equals(ValidationRunner.OPENLB_ARCHIVE)
                || !reference.path("precision").asText().equals("double")) {
            return "missing or incompatible pinned OpenLB reference identity";
        }
        for (var result : report.path("cases")) {
            if (!result.path("id").asText().equals(caseId)) {
                continue;
            }
            if (!result.path("status").asText().equals("PASSED")
                    || !result.path("verified").asBoolean()) {
                return "external case is failed, unavailable, incompatible or unverified";
            }
            var evidence = result.path("evidence");
            if (scope == BenchmarkSuite.ValidationScope.EXACT) {
                if (!evidence.path("caseIdentity").asText().equals(NumericalIdentity.caseIdentity(config))) {
                    return "physical case or diagnostic settings differ from external evidence";
                }
            } else {
                String family = NumericalIdentity.periodicShearIdentity(config);
                if (family == null
                        || !evidence.path("periodicShearIdentity").asText().equals(family)
                        || !hasFeature(result.path("features"), "periodic-streaming")
                        || !hasFeature(result.path("features"), "viscosity")
                        || !hasFeature(result.path("features"), "transient")) {
                    return "missing or mismatched representative periodic-shear coverage";
                }
            }
            if (!evidence.path("toleranceVerification").asText().equals("frozen evidence matches declared limits")
                    || result.path("features").isEmpty()
                    || evidence.path("samples").size() < 2) {
                return "external case lacks verified tolerances, coverage or completed samples";
            }
            return null;
        }
        return "external report does not cover case " + caseId;
    }

    private static boolean hasFeature(JsonNode features, String expected) {
        if (!features.isArray()) {
            return false;
        }
        for (var feature : features) {
            if (feature.isTextual() && feature.asText().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    static void requireEligible(BenchmarkJob job, CfdConfiguration config) throws IOException {
        BenchmarkSuite.require(
                NumericalIdentity.current().equals(job.numericalIdentity()),
                "numerical implementation changed since preflight");
        BenchmarkSuite.require(
                ValidationRunner.candidateIdentity().equals(job.artifactIdentity()),
                "execution artifact changed since backend preflight");
        Path report = Path.of(job.validationReport());
        BenchmarkSuite.require(
                ValidationRunner.sha(report).equals(job.validationSha256()),
                "validation evidence changed since preflight");
        String rejection = rejection(
                BenchmarkSuite.JSON.readTree(report.toFile()),
                job.validationCase(),
                config,
                job.numericalIdentity(),
                job.validationScope());
        BenchmarkSuite.require(rejection == null, rejection);
        BenchmarkSuite.require(
                NumericalIdentity.caseIdentity(config).equals(job.caseIdentity()), "benchmark configuration changed");
        BenchmarkSuite.require(
                config.steps() == Math.addExact(job.preSteps(), job.stepsPerInvocation()),
                "duration must equal preSteps + stepsPerInvocation");
    }
}
