package io.euhedral_execution.benchmarks.cfd.validation;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/// Validation inputs are setup data; no tolerance is fitted from candidate errors.
public record ValidationSuite(int schemaVersion, String outputDirectory, long processDeadlineMillis, List<Case> cases) {
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public ValidationSuite {
        require(schemaVersion == 1, "validation schemaVersion must be 1");
        require(outputDirectory != null && !outputDirectory.isBlank(), "outputDirectory is required");
        require(
                processDeadlineMillis > 0 && processDeadlineMillis <= 3_600_000,
                "process deadline must be 1..3600000 ms");
        require(cases != null, "suite cases are required");
        cases = List.copyOf(cases);
        require(!cases.isEmpty(), "suite needs cases");
        var names = new HashSet<String>();
        for (var fixture : cases) require(names.add(fixture.id()), "duplicate case ID");
    }

    public enum Mode {
        MATCHED_DISCRETIZATION,
        PHYSICAL_CASE_CONVERGENCE
    }

    public enum Status {
        PASSED,
        FAILED,
        UNAVAILABLE,
        INCOMPATIBLE
    }

    public enum Sampling {
        DIRECT,
        TRILINEAR
    }

    public enum Gauge {
        ABSOLUTE,
        FLUID_MEAN
    }

    public record Tolerance(double absolute, double relative, double scale) {
        public Tolerance {
            require(Double.isFinite(absolute) && absolute >= 0, "absolute tolerance must be finite and nonnegative");
            require(Double.isFinite(relative) && relative >= 0, "relative tolerance must be finite and nonnegative");
            require(
                    Double.isFinite(scale) && scale > 0,
                    "each metric needs a positive explicit scale, including zero fields");
            require(Double.isFinite(absolute + relative * scale), "tolerance overflows");
        }

        public double limit() {
            return absolute + relative * scale;
        }
    }

    public record Evidence(String file, String sha256) {
        public Evidence {
            require(file != null && !file.isBlank(), "evidence file required");
            require(sha256 != null && sha256.matches("[0-9a-f]{64}"), "evidence SHA-256 required");
        }
    }

    public record Case(
            String id,
            String config,
            List<String> features,
            Mode mode,
            Gauge pressureGauge,
            List<Long> sampleSteps,
            Map<String, Tolerance> tolerances,
            Evidence evidence,
            String correspondence,
            List<Evidence> refinementEvidence,
            String referenceConfig,
            Sampling sampling,
            Double minimumCoverage) {
        public Case(
                String id,
                String config,
                List<String> features,
                Mode mode,
                Gauge pressureGauge,
                List<Long> sampleSteps,
                Map<String, Tolerance> tolerances,
                Evidence evidence,
                String correspondence,
                List<Evidence> refinementEvidence) {
            this(
                    id,
                    config,
                    features,
                    mode,
                    pressureGauge,
                    sampleSteps,
                    tolerances,
                    evidence,
                    correspondence,
                    refinementEvidence,
                    null,
                    Sampling.DIRECT,
                    1.0);
        }

        public Case {
            require(id != null && id.matches("[a-z0-9][a-z0-9-]*"), "case ID must be a safe lowercase name");
            require(config != null && !config.isBlank(), "case config required");
            require(
                    features != null && sampleSteps != null && tolerances != null,
                    "features, sampleSteps and tolerances are required");
            require(referenceConfig == null || !referenceConfig.isBlank(), "referenceConfig cannot be blank");
            features = List.copyOf(features);
            require(!features.isEmpty(), "feature coverage required");
            require(mode != null && pressureGauge != null, "comparison mode and pressure gauge required");
            require(correspondence != null && !correspondence.isBlank(), "method correspondence required");
            sampleSteps = List.copyOf(sampleSteps);
            require(
                    sampleSteps.size() >= 2 && sampleSteps.getFirst() == 0,
                    "multiple sample times starting at zero required");
            long previous = -1;
            for (long step : sampleSteps) {
                require(
                        step > previous && step <= Integer.MAX_VALUE,
                        "sample steps must be strictly increasing and int-indexable");
                previous = step;
            }
            tolerances = Map.copyOf(tolerances);
            for (String key : List.of("velocity", "density", "pressure", "mass", "force"))
                require(tolerances.containsKey(key), "missing metric tolerance: " + key);
            refinementEvidence = refinementEvidence == null ? List.of() : List.copyOf(refinementEvidence);
            sampling = sampling == null ? Sampling.DIRECT : sampling;
            minimumCoverage = minimumCoverage == null ? 1.0 : minimumCoverage;
            require(
                    Double.isFinite(minimumCoverage) && minimumCoverage > 0 && minimumCoverage <= 1,
                    "coverage must be in (0,1]");
            require(
                    mode != Mode.MATCHED_DISCRETIZATION || sampling == Sampling.DIRECT,
                    "matched discretization requires direct sampling");
        }
    }

    public static ValidationSuite load(Path path) throws IOException {
        return JSON.readValue(Files.readString(path), ValidationSuite.class);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
