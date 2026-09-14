package io.euhedral_execution.benchmarks.cfd.config;

import io.euhedral_execution.benchmarks.cfd.geometry.StlReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record CfdConfiguration(
        Path configFile,
        SimulationConfig config,
        CfdPhysics physics,
        long steps,
        Double physicalDurationSeconds,
        Path outputDirectory,
        MemoryEstimate memory,
        List<StlReader.Info> meshes) {
    public CfdConfiguration {
        Objects.requireNonNull(configFile);
        Objects.requireNonNull(config);
        Objects.requireNonNull(physics);
        Objects.requireNonNull(outputDirectory);
        Objects.requireNonNull(memory);
        meshes = List.copyOf(meshes);
        Checks.require(
                meshes.size() == config.geometry().meshes().size(), "mesh inspection count must match configuration");
        for (int i = 0; i < meshes.size(); i++)
            Checks.require(
                    meshes.get(i).mesh().equals(config.geometry().meshes().get(i)),
                    "mesh inspection must match configuration order");
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
            boolean physicalUnits,
            SimulationConfig.Shear shear,
            UnitConversions units) {
        public CfdPhysics {
            Objects.requireNonNull(units);
            Checks.require(
                    units.lengthScale() == voxelWidth
                            && units.timeScale() == timeStep
                            && units.densityScale() == densityScale,
                    "unit conversions must match resolved physical scales");
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
