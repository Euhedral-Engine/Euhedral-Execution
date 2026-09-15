package io.euhedral_execution.benchmarks.cfd.benchmark;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.execution.BackendOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/// All measurement dimensions are explicit; smoke suites use the same timing boundary as normal suites.
public record BenchmarkSuite(
        int schemaVersion,
        String outputDirectory,
        String validationSuite,
        String validationReport,
        Integer workers,
        String cpus,
        boolean affinity,
        GridShape brick,
        long preSteps,
        long stepsPerInvocation,
        int forks,
        int warmupIterations,
        int measurementIterations,
        long iterationMillis,
        long processDeadlineMillis,
        double maxForkCv,
        List<String> jvmArgs,
        String baselineVariant,
        String referenceBackend,
        List<Variant> variants,
        List<Case> cases,
        Integer bricksPerFrame) {
    public static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public BenchmarkSuite {
        bricksPerFrame = bricksPerFrame == null ? 1 : bricksPerFrame;
        require(bricksPerFrame > 0, "bricksPerFrame must be positive");
        require(schemaVersion == 1, "benchmark schemaVersion must be 1");
        require(outputDirectory != null && !outputDirectory.isBlank(), "outputDirectory is required");
        require((validationSuite == null) != (validationReport == null), "select validationSuite or validationReport");
        require(workers == null || workers > 0, "workers must be positive");
        require(brick != null, "brick is required");
        require(
                preSteps >= 0 && stepsPerInvocation > 0 && preSteps <= Long.MAX_VALUE - stepsPerInvocation,
                "invalid invocation work");
        require(
                forks > 0 && forks <= 100 && warmupIterations >= 1 && measurementIterations >= 1,
                "positive forks, warmup and measurement iterations required");
        require(
                iterationMillis > 0
                        && iterationMillis <= 3_600_000
                        && processDeadlineMillis > 0
                        && processDeadlineMillis <= 604_800_000,
                "invalid timing settings");
        require(Double.isFinite(maxForkCv) && maxForkCv > 0, "maxForkCv must be finite and positive");
        require(jvmArgs != null && variants != null && cases != null, "jvmArgs, variants and cases are required");
        jvmArgs = List.copyOf(jvmArgs);
        for (String arg : jvmArgs) {
            require(arg != null && !arg.isBlank(), "empty JVM argument");
            require(
                    !arg.startsWith("-Dcfd.")
                            && !arg.equals("-cp")
                            && !arg.equals("-classpath")
                            && !arg.equals("--class-path"),
                    "JVM arguments cannot override benchmark identity/classpath");
        }
        variants = List.copyOf(variants);
        cases = List.copyOf(cases);
        require(!cases.isEmpty() && !variants.isEmpty(), "cases and variants required");
        var ids = new HashSet<String>();
        for (var variant : variants) {
            require(ids.add(variant.id()), "duplicate variant ID");
        }
        require(ids.contains(baselineVariant), "baselineVariant must name an explicit variant");
        referenceBackend = new BackendOptions(referenceBackend, null, null, null, false, null).backend();
        ids.clear();
        for (var fixture : cases) {
            require(ids.add(fixture.id()), "duplicate case ID");
        }
    }

    public record Variant(String id, String backend, Object sources, GridShape brick) {
        public Variant(String id, String backend, Object sources) {
            this(id, backend, sources, null);
        }

        public GridShape brickOrDefault(GridShape fallback) {
            return brick == null ? fallback : brick;
        }

        public Variant {
            safeId(id);
            var checked = new BackendOptions(backend, null, sources, null, false, null);
            backend = checked.backend();
            sources = checked.sources();
        }
    }

    public enum ValidationScope {
        EXACT,
        PERIODIC_SHEAR_FAMILY
    }

    public record Case(String id, String config, String validationCase, ValidationScope validationScope) {
        public Case {
            safeId(id);
            safeId(validationCase);
            validationScope = validationScope == null ? ValidationScope.EXACT : validationScope;
            require(config != null && !config.isBlank(), "case config required");
        }
    }

    public static BenchmarkSuite load(Path file) throws IOException {
        return JSON.readValue(file.toFile(), BenchmarkSuite.class);
    }

    static void safeId(String id) {
        require(id != null && id.matches("[a-z0-9][a-z0-9-]*"), "IDs must be safe lowercase names");
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
