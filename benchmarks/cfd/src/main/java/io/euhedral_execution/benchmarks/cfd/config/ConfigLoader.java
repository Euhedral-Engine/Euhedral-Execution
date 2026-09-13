package io.euhedral_execution.benchmarks.cfd.config;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration.CfdPhysics;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
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
        SimulationConfig config = read(path);
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
        return resolve(path, read(path), defaultBudgetBytes);
    }

    private static SimulationConfig read(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        JsonNode tree = MAPPER.readTree(absolute.toFile());
        Checks.require(tree != null && tree.isObject(), "configuration must be a JSON object");
        rejectNulls(tree, "");
        return MAPPER.treeToValue(tree, SimulationConfig.class);
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
        double viscosity;
        Vector3 velocity;
        Vector3 acceleration;
        double referenceLength = config.referenceLength() == null ? 0 : config.referenceLength();
        if (config.physical() != null) {
            var physical = config.physical();
            dx = physical.voxelWidth();
            dt = physical.timeStep();
            double velocityScale = Checks.positive(dt / dx, "velocity conversion");
            viscosity = Checks.positive(physical.viscosity() * velocityScale / dx, "converted viscosity");
            velocity = physical.initialVelocity().scale(velocityScale);
            acceleration =
                    physical.acceleration().scale(Checks.positive(velocityScale * dt, "acceleration conversion"));
            densityScale =
                    Checks.positive(physical.densityReference() / config.densityReference(), "density conversion");
            if (config.referenceLength() != null)
                referenceLength = Checks.positive(referenceLength / dx, "converted referenceLength");
        } else {
            viscosity = config.lattice().viscosity();
            velocity = config.lattice().initialVelocity();
            acceleration = config.lattice().acceleration();
        }
        double tau = Checks.finite(0.5 + 3 * viscosity, "resolved tau");
        Checks.require(tau > 0.5, "resolved tau must be greater than 0.5");
        double mach = Checks.finite(velocity.magnitude() * Math.sqrt(3), "initial Mach");
        Double reynolds = config.referenceLength() == null
                ? null
                : Checks.finite(velocity.magnitude() * referenceLength / viscosity, "Reynolds number");
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
                config.physical() != null);
    }
}
