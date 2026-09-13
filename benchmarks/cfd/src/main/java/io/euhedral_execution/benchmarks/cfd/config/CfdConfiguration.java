package io.euhedral_execution.benchmarks.cfd.config;

import java.nio.file.Path;
import java.util.Objects;

public record CfdConfiguration(
        Path configFile,
        SimulationConfig config,
        CfdPhysics physics,
        long steps,
        Double physicalDurationSeconds,
        Path outputDirectory,
        MemoryEstimate memory) {
    public CfdConfiguration {
        Objects.requireNonNull(configFile);
        Objects.requireNonNull(config);
        Objects.requireNonNull(physics);
        Objects.requireNonNull(outputDirectory);
        Objects.requireNonNull(memory);
        Checks.require(steps > 0, "resolved steps must be positive");
        if (physicalDurationSeconds != null) Checks.positive(physicalDurationSeconds, "resolved physical duration");
        memory.requireAllocatable(config.grid());
    }

    public record CfdPhysics(
            double densityReference,
            double viscosity,
            double tau,
            Vector3 initialVelocity,
            Vector3 acceleration,
            double initialMach,
            Double reynolds,
            double voxelWidth,
            double timeStep,
            double densityScale,
            boolean physicalUnits) {
        public CfdPhysics {
            Checks.positive(densityReference, "resolved densityReference");
            Checks.positive(viscosity, "resolved viscosity");
            Checks.finite(tau, "resolved tau");
            Checks.require(tau > 0.5, "resolved tau must be greater than 0.5");
            Objects.requireNonNull(initialVelocity);
            Objects.requireNonNull(acceleration);
            Checks.finite(initialMach, "initial Mach");
            Checks.require(initialMach >= 0, "initial Mach must be non-negative");
            if (reynolds != null) {
                Checks.finite(reynolds, "Reynolds number");
                Checks.require(reynolds >= 0, "Reynolds number must be non-negative");
            }
            Checks.positive(voxelWidth, "resolved voxelWidth");
            Checks.positive(timeStep, "resolved timeStep");
            Checks.positive(densityScale, "resolved densityScale");
        }
    }
}
