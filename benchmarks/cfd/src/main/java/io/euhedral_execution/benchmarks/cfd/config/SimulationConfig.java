package io.euhedral_execution.benchmarks.cfd.config;

import java.util.HashSet;
import java.util.List;
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
            Double densityReference,
            Lattice lattice,
            Physical physical,
            Double referenceLength,
            Shear shear,
            Guards guards,
            ForceReference forceReference) {
        public Physics(
                Double densityReference,
                Lattice lattice,
                Physical physical,
                Double referenceLength,
                Shear shear,
                Guards guards) {
            this(densityReference, lattice, physical, referenceLength, shear, guards, null);
        }

        public Physics(
                Double densityReference, Lattice lattice, Physical physical, Double referenceLength, Shear shear) {
            this(densityReference, lattice, physical, referenceLength, shear, null);
        }

        public Physics(Double densityReference, Lattice lattice, Physical physical, Double referenceLength) {
            this(densityReference, lattice, physical, referenceLength, null);
        }

        public Physics {
            guards = guards == null ? Guards.DEFAULT : guards;
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
        WALL,
        VELOCITY_INLET,
        DENSITY_OUTLET
    }

    public record Faces(
            FaceCondition xMin,
            FaceCondition xMax,
            FaceCondition yMin,
            FaceCondition yMax,
            FaceCondition zMin,
            FaceCondition zMax) {
        public FaceCondition at(int face) {
            return switch (face) {
                case 0 -> xMin;
                case 1 -> xMax;
                case 2 -> yMin;
                case 3 -> yMax;
                case 4 -> zMin;
                case 5 -> zMax;
                default -> throw new IndexOutOfBoundsException(face);
            };
        }

        public int openAxis() {
            for (int face = 0; face < 6; face++)
                if (at(face) == FaceCondition.VELOCITY_INLET || at(face) == FaceCondition.DENSITY_OUTLET)
                    return face / 2;
            return -1;
        }

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

    /// Coordinates and radii use the selected parameter mode: lattice lengths or meters.
    /// Positive IDs are unique across primitives; the lowest ID wins at overlapping cell centers.
    public record Geometry(
            Faces faces,
            List<Box> boxes,
            List<Sphere> spheres,
            List<Cylinder> cylinders,
            OpenBoundary openBoundary,
            List<Mesh> meshes) {
        public Geometry(
                Faces faces,
                List<Box> boxes,
                List<Sphere> spheres,
                List<Cylinder> cylinders,
                OpenBoundary openBoundary) {
            this(faces, boxes, spheres, cylinders, openBoundary, null);
        }

        public Geometry(Faces faces, List<Box> boxes, List<Sphere> spheres, List<Cylinder> cylinders) {
            this(faces, boxes, spheres, cylinders, null);
        }

        public Geometry(Faces faces) {
            this(faces, null, null, null);
        }

        public Geometry {
            faces = faces == null ? new Faces(null, null, null, null, null, null) : faces;
            boxes = boxes == null ? List.of() : List.copyOf(boxes);
            spheres = spheres == null ? List.of() : List.copyOf(spheres);
            cylinders = cylinders == null ? List.of() : List.copyOf(cylinders);
            meshes = meshes == null ? List.of() : List.copyOf(meshes);
            int axis = faces.openAxis();
            Checks.require(
                    (axis >= 0) == (openBoundary != null), "open faces require geometry.openBoundary and vice versa");
            if (axis >= 0) {
                for (int a = 0; a < 3; a++) {
                    var min = faces.at(2 * a);
                    var max = faces.at(2 * a + 1);
                    Checks.require(
                            a == axis
                                    ? (min == FaceCondition.VELOCITY_INLET && max == FaceCondition.DENSITY_OUTLET)
                                            || (max == FaceCondition.VELOCITY_INLET
                                                    && min == FaceCondition.DENSITY_OUTLET)
                                    : min == max && (min == FaceCondition.WALL || min == FaceCondition.PERIODIC),
                            "supported open faces are one opposing velocity inlet/density outlet pair; transverse faces must be paired walls or periodic");
                }
            }
            var ids = new HashSet<Integer>();
            for (Box box : boxes) Checks.require(ids.add(box.id()), "duplicate obstacle ID " + box.id());
            for (Sphere sphere : spheres) Checks.require(ids.add(sphere.id()), "duplicate obstacle ID " + sphere.id());
            for (Cylinder cylinder : cylinders)
                Checks.require(ids.add(cylinder.id()), "duplicate obstacle ID " + cylinder.id());
            var names = new HashSet<String>();
            for (Mesh mesh : meshes) {
                Checks.require(ids.add(mesh.id()), "duplicate obstacle ID " + mesh.id());
                Checks.require(names.add(mesh.name()), "duplicate mesh name " + mesh.name());
            }
        }
    }

    public enum MeshUnits {
        LATTICE,
        METERS,
        MILLIMETERS,
        CENTIMETERS,
        INCHES
    }

    /// Apply unit conversion and positive component scale, then X/Y/Z rotations in degrees,
    /// then translation in the simulation's coordinate units. Welding also uses those units.
    public record Mesh(
            int id,
            String name,
            String file,
            MeshUnits units,
            Vector3 scale,
            Vector3 rotationDegrees,
            Vector3 translation,
            Double weldTolerance) {
        public Mesh {
            Checks.require(id > 0, "obstacle ID must be positive");
            Checks.require(file != null && !file.isBlank(), "mesh.file is required");
            name = name == null ? "mesh-" + id : name;
            Checks.require(!name.isBlank(), "mesh.name must not be blank");
            Objects.requireNonNull(units, "mesh.units is required");
            scale = scale == null ? new Vector3(1, 1, 1) : scale;
            Checks.positive(scale.x(), "mesh scale.x");
            Checks.positive(scale.y(), "mesh scale.y");
            Checks.positive(scale.z(), "mesh scale.z");
            rotationDegrees = rotationDegrees == null ? Vector3.ZERO : rotationDegrees;
            translation = translation == null ? Vector3.ZERO : translation;
            if (weldTolerance != null) Checks.positive(weldTolerance, "mesh.weldTolerance");
        }
    }

    /// Velocity, density and ramp time use the selected lattice or physical unit mode.
    public record OpenBoundary(Vector3 velocity, Double outletDensity, Double rampTime) {
        public OpenBoundary {
            Objects.requireNonNull(velocity, "openBoundary.velocity is required");
            if (outletDensity != null) Checks.positive(outletDensity, "openBoundary.outletDensity");
            rampTime = rampTime == null ? 0 : rampTime;
            Checks.finite(rampTime, "openBoundary.rampTime");
            Checks.require(rampTime >= 0, "openBoundary.rampTime must be non-negative");
        }
    }

    /// Drag references are independent of the initial state and inlet ramp.
    public record ForceReference(double velocity, double area, double density, Vector3 direction) {
        public ForceReference {
            Checks.positive(velocity, "forceReference.velocity");
            Checks.positive(area, "forceReference.area");
            Checks.positive(density, "forceReference.density");
            Objects.requireNonNull(direction, "forceReference.direction is required");
            Checks.positive(direction.magnitude(), "forceReference.direction magnitude");
        }
    }

    public record Box(int id, Vector3 min, Vector3 max) {
        public Box {
            Checks.require(id > 0, "obstacle ID must be positive");
            Objects.requireNonNull(min);
            Objects.requireNonNull(max);
            Checks.positive(max.x() - min.x(), "box x extent");
            Checks.positive(max.y() - min.y(), "box y extent");
            Checks.positive(max.z() - min.z(), "box z extent");
        }
    }

    public record Sphere(int id, Vector3 center, double radius) {
        public Sphere {
            Checks.require(id > 0, "obstacle ID must be positive");
            Objects.requireNonNull(center);
            Checks.positive(radius, "sphere radius");
        }
    }

    /// A closed finite cylinder with flat end caps, oriented from start to end.
    public record Cylinder(int id, Vector3 start, Vector3 end, double radius) {
        public Cylinder {
            Checks.require(id > 0, "obstacle ID must be positive");
            Objects.requireNonNull(start);
            Objects.requireNonNull(end);
            Checks.positive(radius, "cylinder radius");
            Checks.positive(
                    Math.hypot(Math.hypot(end.x() - start.x(), end.y() - start.y()), end.z() - start.z()),
                    "cylinder length");
        }
    }

    public record Guards(Double maxMach, Double maxRelativeDensityVariation) {
        public static final Guards DEFAULT = new Guards(0.1, 0.1);

        public Guards {
            maxMach = maxMach == null ? 0.1 : maxMach;
            maxRelativeDensityVariation = maxRelativeDensityVariation == null ? 0.1 : maxRelativeDensityVariation;
            Checks.positive(maxMach, "guards.maxMach");
            Checks.positive(maxRelativeDensityVariation, "guards.maxRelativeDensityVariation");
        }
    }

    public record Execution(
            Long steps,
            Double durationSeconds,
            Long stepDeadlineMillis,
            GridShape brick,
            Long diagnosticsEverySteps,
            Integer geometrySources,
            Long geometryDeadlineMillis) {
        public Execution(
                Long steps,
                Double durationSeconds,
                Long stepDeadlineMillis,
                GridShape brick,
                Long diagnosticsEverySteps) {
            this(steps, durationSeconds, stepDeadlineMillis, brick, diagnosticsEverySteps, null, null);
        }

        public Execution(Long steps, Double durationSeconds, Long stepDeadlineMillis, GridShape brick) {
            this(steps, durationSeconds, stepDeadlineMillis, brick, null);
        }

        public Execution {
            if (geometrySources != null)
                Checks.require(
                        geometrySources > 0 && geometrySources <= 64, "geometrySources must be between 1 and 64");
            geometryDeadlineMillis = geometryDeadlineMillis == null ? 300_000L : geometryDeadlineMillis;
            Checks.require(
                    geometryDeadlineMillis > 0 && geometryDeadlineMillis <= Long.MAX_VALUE / 1_000_000,
                    "geometryDeadlineMillis must be positive and fit nanoseconds");
            diagnosticsEverySteps = diagnosticsEverySteps == null ? 1L : diagnosticsEverySteps;
            Checks.require(diagnosticsEverySteps >= 0, "execution.diagnosticsEverySteps must be non-negative");
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

    public enum FieldFormat {
        APPENDED,
        ASCII
    }

    public record Output(String directory, Long exportEverySteps, FieldFormat format) {
        public Output(String directory, Long exportEverySteps) {
            this(directory, exportEverySteps, null);
        }

        public Output {
            format = format == null ? FieldFormat.APPENDED : format;
            directory = directory == null ? "output" : directory;
            Checks.require(!directory.isBlank(), "output.directory must not be blank");
            exportEverySteps = exportEverySteps == null ? 0L : exportEverySteps;
            Checks.require(exportEverySteps >= 0, "output.exportEverySteps must be non-negative");
        }
    }
}
