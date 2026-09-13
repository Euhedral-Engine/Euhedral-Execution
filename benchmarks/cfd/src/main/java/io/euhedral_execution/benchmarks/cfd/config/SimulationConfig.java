package io.euhedral_execution.benchmarks.cfd.config;

import java.util.Objects;

/// Schema version 1. Optional omitted sections have deterministic defaults.
public record SimulationConfig(
        int schemaVersion,
        GridShape grid,
        Physics physics,
        Geometry geometry,
        Execution execution,
        Output output,
        Long memoryLimitBytes) {
    public SimulationConfig {
        Checks.require(schemaVersion == 1, "schemaVersion must be 1");
        Objects.requireNonNull(grid, "grid is required");
        Checks.require(
                grid.nx() >= 3 && grid.ny() >= 3 && grid.nz() >= 3,
                "simulation grid must have at least 3 cells on every axis");
        physics = physics == null ? new Physics(null, null, null, null) : physics;
        if (physics.shear() != null) {
            Checks.require(
                    2L * physics.shear().modeY() < grid.ny()
                            && 2L * physics.shear().modeZ() < grid.nz(),
                    "shear modes must be below the Nyquist frequency on Y and Z");
        }
        geometry = geometry == null ? new Geometry(null) : geometry;
        execution = execution == null ? new Execution(null, null, null, null) : execution;
        output = output == null ? new Output(null, null) : output;
        if (memoryLimitBytes != null) Checks.require(memoryLimitBytes > 0, "memoryLimitBytes must be positive");
        Checks.require(
                execution.durationSeconds() == null || physics.physical() != null,
                "durationSeconds requires physical parameters");
    }

    public record Physics(
            Double densityReference, Lattice lattice, Physical physical, Double referenceLength, Shear shear) {
        public Physics(Double densityReference, Lattice lattice, Physical physical, Double referenceLength) {
            this(densityReference, lattice, physical, referenceLength, null);
        }

        public Physics {
            densityReference = densityReference == null ? 1.0 : densityReference;
            Checks.positive(densityReference, "physics.densityReference");
            Checks.require(
                    lattice == null || physical == null, "lattice and physical parameter modes are mutually exclusive");
            if (lattice == null && physical == null) lattice = new Lattice(null, null, null);
            if (referenceLength != null) Checks.positive(referenceLength, "physics.referenceLength");
            if (shear != null) {
                Vector3 velocity = physical == null ? lattice.initialVelocity() : physical.initialVelocity();
                Checks.require(velocity.magnitude() == 0, "shear and nonzero initialVelocity are mutually exclusive");
            }
        }
    }

    public record Shear(double amplitude, Integer modeY, Integer modeZ) {
        public Shear {
            Checks.positive(amplitude, "shear.amplitude");
            modeY = modeY == null ? 1 : modeY;
            modeZ = modeZ == null ? 1 : modeZ;
            Checks.require(modeY > 0 && modeZ > 0, "shear modes must be positive");
        }
    }

    public record Lattice(Double viscosity, Vector3 initialVelocity, Vector3 acceleration) {
        public Lattice {
            viscosity = viscosity == null ? 0.1 : viscosity;
            Checks.positive(viscosity, "lattice.viscosity");
            initialVelocity = initialVelocity == null ? Vector3.ZERO : initialVelocity;
            acceleration = acceleration == null ? Vector3.ZERO : acceleration;
        }
    }

    public record Physical(
            double voxelWidth,
            double timeStep,
            double densityReference,
            double viscosity,
            Vector3 initialVelocity,
            Vector3 acceleration) {
        public Physical {
            Checks.positive(voxelWidth, "physical.voxelWidth");
            Checks.positive(timeStep, "physical.timeStep");
            Checks.positive(densityReference, "physical.densityReference");
            Checks.positive(viscosity, "physical.viscosity");
            initialVelocity = initialVelocity == null ? Vector3.ZERO : initialVelocity;
            acceleration = acceleration == null ? Vector3.ZERO : acceleration;
        }
    }

    public enum FaceCondition {
        PERIODIC,
        WALL
    }

    public record Faces(
            FaceCondition xMin,
            FaceCondition xMax,
            FaceCondition yMin,
            FaceCondition yMax,
            FaceCondition zMin,
            FaceCondition zMax) {
        public Faces {
            xMin = xMin == null ? FaceCondition.PERIODIC : xMin;
            xMax = xMax == null ? FaceCondition.PERIODIC : xMax;
            yMin = yMin == null ? FaceCondition.PERIODIC : yMin;
            yMax = yMax == null ? FaceCondition.PERIODIC : yMax;
            zMin = zMin == null ? FaceCondition.PERIODIC : zMin;
            zMax = zMax == null ? FaceCondition.PERIODIC : zMax;
            checkPair(xMin, xMax, "x");
            checkPair(yMin, yMax, "y");
            checkPair(zMin, zMax, "z");
        }

        private static void checkPair(FaceCondition min, FaceCondition max, String axis) {
            Checks.require(
                    (min == FaceCondition.PERIODIC) == (max == FaceCondition.PERIODIC),
                    "periodic faces must be paired on axis " + axis);
        }
    }

    /// Domain faces only; obstacle definitions are added with the geometry implementation.
    public record Geometry(Faces faces) {
        public Geometry {
            faces = faces == null ? new Faces(null, null, null, null, null, null) : faces;
        }
    }

    public record Execution(Long steps, Double durationSeconds, Long stepDeadlineMillis, GridShape brick) {
        public Execution {
            Checks.require(
                    steps == null || durationSeconds == null, "steps and durationSeconds are mutually exclusive");
            if (durationSeconds != null) Checks.positive(durationSeconds, "execution.durationSeconds");
            if (steps == null && durationSeconds == null) steps = 100L;
            if (steps != null) Checks.require(steps > 0, "execution.steps must be positive");
            stepDeadlineMillis = stepDeadlineMillis == null ? 30_000L : stepDeadlineMillis;
            Checks.require(
                    stepDeadlineMillis > 0 && stepDeadlineMillis <= Long.MAX_VALUE / 1_000_000,
                    "execution.stepDeadlineMillis must be positive and fit nanoseconds");
            brick = brick == null ? new GridShape(16, 16, 16) : brick;
        }
    }

    public record Output(String directory, Long exportEverySteps) {
        public Output {
            directory = directory == null ? "output" : directory;
            Checks.require(!directory.isBlank(), "output.directory must not be blank");
            exportEverySteps = exportEverySteps == null ? 0L : exportEverySteps;
            Checks.require(exportEverySteps >= 0, "output.exportEverySteps must be non-negative");
        }
    }
}
