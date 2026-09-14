package io.euhedral_execution.benchmarks.cfd.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration.CfdPhysics;
import io.euhedral_execution.benchmarks.cfd.solver.FlowDiagnostics;
import io.euhedral_execution.benchmarks.cfd.solver.OpenBoundaries;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/// Parses and resolves small configuration objects without creating workers or population arrays.
public final class ConfigLoader {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    static {
        MAPPER.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    private ConfigLoader() {}

    public static CfdConfiguration load(Path path) throws IOException {
        return load(path, List.of());
    }

    public static CfdConfiguration load(Path path, List<String> overrides) throws IOException {
        SimulationConfig config = read(path, overrides);
        long budget;
        if (config.memoryLimitBytes() != null) {
            budget = config.memoryLimitBytes();
        } else {
            /// A single snapshot avoids totalMemory/freeMemory disagreeing across a GC heap resize.
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long max = heap.getMax() > 0 ? heap.getMax() : Runtime.getRuntime().maxMemory();
            budget = MemoryEstimate.defaultBudget(max, heap.getUsed());
        }
        return resolve(path, config, budget);
    }

    public static CfdConfiguration load(Path path, long defaultBudgetBytes) throws IOException {
        return resolve(path, read(path, List.of()), defaultBudgetBytes);
    }

    private static SimulationConfig read(Path path, List<String> overrides) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        JsonNode tree = MAPPER.readTree(absolute.toFile());
        Checks.require(tree != null && tree.isObject(), "configuration must be a JSON object");
        rejectNulls(tree, "");
        /// Override paths use schema property names; the strict record parser validates the result.
        var paths = new HashSet<String>();
        for (String override : overrides) {
            int equals = override.indexOf('=');
            Checks.require(equals > 0, "override must be path=JSON-value");
            String pathKey = override.substring(0, equals);
            Checks.require(paths.add(pathKey), "duplicate override: " + pathKey);
            Checks.require(
                    !(paths.contains("execution.steps") && paths.contains("execution.durationSeconds")),
                    "steps and durationSeconds overrides are mutually exclusive");
            String[] parts = pathKey.split("\\.", -1);
            ObjectNode parent = (ObjectNode) tree;
            for (int i = 0; i < parts.length - 1; i++) {
                Checks.require(parts[i].matches("[A-Za-z][A-Za-z0-9]*"), "invalid override path");
                JsonNode child = parent.get(parts[i]);
                if (child == null) child = parent.putObject(parts[i]);
                Checks.require(child.isObject(), "override parent must be an object: " + parts[i]);
                parent = (ObjectNode) child;
            }
            String field = parts[parts.length - 1];
            Checks.require(field.matches("[A-Za-z][A-Za-z0-9]*"), "invalid override path");
            JsonNode value = MAPPER.readTree(override.substring(equals + 1));
            Checks.require(value != null, "override needs a JSON value");
            rejectNulls(value, override.substring(0, equals));
            if (parts.length == 2 && parts[0].equals("execution")) {
                if (field.equals("steps")) parent.remove("durationSeconds");
                if (field.equals("durationSeconds")) parent.remove("steps");
            }
            parent.set(field, value);
        }
        return MAPPER.treeToValue(tree, SimulationConfig.class);
    }

    public static String json(Object value) throws IOException {
        return MAPPER.copy()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(value);
    }

    private static void rejectNulls(JsonNode node, String path) {
        Checks.require(!node.isNull(), path + " must not be null; omit optional fields to use defaults");
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                rejectNulls(field.getValue(), path + "/" + field.getKey());
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) rejectNulls(node.get(i), path + "/" + i);
        }
    }

    public static CfdConfiguration resolve(Path path, SimulationConfig config, long defaultBudgetBytes) {
        CfdPhysics physics = resolvePhysics(config.physics());
        for (int size :
                new int[] {config.grid().nx(), config.grid().ny(), config.grid().nz()})
            physics.units().lengthToPhysical(size);
        OpenBoundaries.resolve(config, physics);
        FlowDiagnostics.validateReference(config.physics().forceReference(), physics);
        long steps;
        if (config.execution().durationSeconds() == null) {
            steps = config.execution().steps();
        } else {
            double requiredSteps = Math.ceil(config.execution().durationSeconds() / physics.timeStep());
            Checks.require(
                    Double.isFinite(requiredSteps) && requiredSteps >= 1 && requiredSteps < 0x1.0p63,
                    "physical duration does not resolve to a positive long step count");
            steps = (long) requiredSteps;
        }
        try {
            Math.multiplyExact(config.grid().cellCount(), steps);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "cell update count overflows for grid " + config.grid() + " and steps=" + steps, e);
        }
        Double duration =
                physics.physicalUnits() ? Checks.positive(steps * physics.timeStep(), "physical duration") : null;
        Path absolute = path.toAbsolutePath().normalize();
        Path output = absolute.getParent().resolve(config.output().directory()).normalize();
        MemoryEstimate memory = MemoryEstimate.estimate(config, defaultBudgetBytes);
        return new CfdConfiguration(absolute, config, physics, steps, duration, output, memory);
    }

    private static CfdPhysics resolvePhysics(SimulationConfig.Physics config) {
        double dx = 1, dt = 1, densityScale = 1;
        UnitConversions units = UnitConversions.of(1, 1, 1);
        double viscosity;
        Vector3 velocity;
        Vector3 acceleration;
        double referenceLength = config.referenceLength() == null ? 0 : config.referenceLength();
        if (config.physical() != null) {
            var physical = config.physical();
            dx = physical.voxelWidth();
            dt = physical.timeStep();
            densityScale =
                    Checks.positive(physical.densityReference() / config.densityReference(), "density conversion");
            units = UnitConversions.of(dx, dt, densityScale);
            viscosity = Checks.positive(units.viscosityToLattice(physical.viscosity()), "converted viscosity");
            velocity = units.velocityToLattice(physical.initialVelocity());
            acceleration = units.accelerationToLattice(physical.acceleration());
            if (config.referenceLength() != null)
                referenceLength = Checks.positive(units.lengthToLattice(referenceLength), "converted referenceLength");
        } else {
            viscosity = config.lattice().viscosity();
            velocity = config.lattice().initialVelocity();
            acceleration = config.lattice().acceleration();
        }
        double tau = Checks.finite(0.5 + 3 * viscosity, "resolved tau");
        Checks.require(tau > 0.5, "resolved tau must be greater than 0.5");
        SimulationConfig.Shear shear = config.shear() == null
                ? null
                : new SimulationConfig.Shear(
                        units.velocityToLattice(config.shear().amplitude()),
                        config.shear().modeY(),
                        config.shear().modeZ());
        double referenceSpeed = shear == null ? velocity.magnitude() : shear.amplitude();
        double mach = Checks.finite(referenceSpeed * Math.sqrt(3), "initial Mach");
        Double reynolds = config.referenceLength() == null
                ? null
                : Checks.finite(referenceSpeed * referenceLength / viscosity, "Reynolds number");
        return new CfdPhysics(
                config.densityReference(),
                viscosity,
                tau,
                velocity,
                acceleration,
                mach,
                reynolds,
                dx,
                dt,
                densityScale,
                config.physical() != null,
                shear,
                units);
    }
}
