package io.euhedral_execution.benchmarks.cfd.validation;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig;
import io.euhedral_execution.benchmarks.cfd.solver.SerialSimulation;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationState;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Map;

/// A separate JVM gives the parent a bounded deadline even during native startup and shutdown.
public final class ValidationWorker {
    private ValidationWorker() {}

    public static void main(String[] args) throws IOException {
        var fixture = ValidationSuite.JSON.readValue(Path.of(args[0]).toFile(), ValidationSuite.Case.class);
        Path configPath = Path.of(args[1]), directory = Path.of(args[2]);
        var config = ConfigLoader.load(configPath);
        ValidationSuite.require(
                fixture.sampleSteps().getLast() == config.steps(), "last sample must equal configured steps");
        Files.createDirectories(directory);
        var defaults = LatticeConfig.ofDefaults();
        BitSet allowed = (BitSet) SystemInfo.getCpuSet().clone();
        allowed.and(ThreadTools.BASE_MASK);
        int cpu = allowed.nextSetBit(0);
        if (cpu < 0) throw new IllegalStateException("no available validation worker CPU");
        BitSet selected = new BitSet();
        selected.set(cpu);
        var lattice = ControlPlaneLattice.getOrCreate(
                new LatticeConfig(defaults.name(), selected, defaults.shutdownTimeout(), defaults.baseShard()));
        try (var simulation = new SerialSimulation(config, lattice)) {
            Files.writeString(directory.resolve("resolved.json"), ConfigLoader.json(config));
            Files.writeString(directory.resolve("configuration.json"), ConfigLoader.replayJson(config));
            Files.writeString(directory.resolve("method.json"), ConfigLoader.json(method(config)));
            writeInput(directory.resolve("input.txt"), fixture, config, simulation.state());
            if (args.length == 4 && args[3].equals("prepare-only")) {
                Files.writeString(directory.resolve("completed"), "1\n");
                return;
            }
            int sample = 0;
            while (true) {
                var state = simulation.state();
                if (state.completedSteps() == fixture.sampleSteps().get(sample)) {
                    export(directory, state);
                    sample++;
                    if (sample == fixture.sampleSteps().size()) break;
                }
                simulation.step();
            }
            Files.writeString(directory.resolve("completed"), "1\n");
        } finally {
            lattice.close();
        }
    }

    static Map<String, Object> method(CfdConfiguration config) {
        var p = config.physics();
        return Map.of(
                "stencil",
                "D3Q19",
                "collision",
                "BGK-second-order",
                "tau",
                p.tau(),
                "densityReference",
                p.densityReference(),
                "force",
                "Guo",
                "wall",
                "halfway-link",
                "population",
                "post-collision",
                "velocityCorrection",
                "minus-half-acceleration",
                "openBoundary",
                config.config().geometry().faces().openAxis() < 0 ? "none" : "Hecht-Harting",
                "implementation",
                "Java CfdRangeFrame; Hecht-Harting open faces when configured");
    }

    private static void writeInput(Path path, ValidationSuite.Case fixture, CfdConfiguration c, SimulationState state)
            throws IOException {
        var p = c.physics();
        var grid = state.shape();
        try (var out = Files.newBufferedWriter(path)) {
            out.write("1 " + grid.nx() + " " + grid.ny() + " " + grid.nz() + " " + c.steps() + " "
                    + fixture.sampleSteps().size() + "\n");
            out.write(p.tau() + " " + p.densityReference() + " " + p.voxelWidth() + " " + p.timeStep() + " "
                    + p.densityScale() + "\n");
            out.write(p.initialVelocity().x() + " " + p.initialVelocity().y() + " "
                    + p.initialVelocity().z() + "\n");
            out.write(p.acceleration().x() + " " + p.acceleration().y() + " "
                    + p.acceleration().z() + "\n");
            var shear = p.shear();
            out.write(shear == null ? "0 0 0\n" : shear.amplitude() + " " + shear.modeY() + " " + shear.modeZ() + "\n");
            for (int face = 0; face < 6; face++) {
                var f = c.config().geometry().faces().at(face);
                out.write((f == SimulationConfig.FaceCondition.PERIODIC
                                ? 0
                                : f == SimulationConfig.FaceCondition.WALL ? 1 : 2)
                        + " ");
            }
            out.newLine();
            for (long step : fixture.sampleSteps()) out.write(step + " ");
            out.newLine();
            for (int i = 0; i < grid.cellCount(); i++)
                out.write(state.geometry().obstacleId(i) + "\n");
            var boundary = io.euhedral_execution.benchmarks.cfd.solver.OpenBoundaries.resolve(c);
            out.write(
                    boundary == null
                            ? "-1 0 0 0 1 0\n"
                            : boundary.inletFace() + " " + boundary.velocity(0) + " " + boundary.velocity(1) + " "
                                    + boundary.velocity(2) + " " + boundary.outletDensity() + " " + boundary.rampSteps()
                                    + "\n");
            var geometry = c.config().geometry();
            out.write(geometry.boxes().size() + " " + geometry.spheres().size() + " "
                    + geometry.cylinders().size() + " " + c.meshes().size() + "\n");
            for (var box : geometry.boxes())
                out.write(box.id() + " " + vector(box.min()) + " " + vector(box.max()) + "\n");
            for (var sphere : geometry.spheres())
                out.write(sphere.id() + " " + vector(sphere.center()) + " " + sphere.radius() + "\n");
            for (var cylinder : geometry.cylinders())
                out.write(cylinder.id() + " " + vector(cylinder.start()) + " " + vector(cylinder.end()) + " "
                        + cylinder.radius() + "\n");
            for (var info : c.meshes()) {
                var mesh = info.mesh();
                double unit =
                        switch (mesh.units()) {
                            case LATTICE -> p.voxelWidth();
                            case METERS -> 1;
                            case MILLIMETERS -> .001;
                            case CENTIMETERS -> .01;
                            case INCHES -> .0254;
                        };
                out.write(mesh.id() + " "
                        + ConfigLoader.json(c.configFile()
                                        .getParent()
                                        .resolve(mesh.file())
                                        .normalize()
                                        .toString())
                                .strip()
                        + " " + unit + " " + vector(mesh.scale()) + " " + vector(mesh.rotationDegrees()) + " "
                        + vector(mesh.translation()) + "\n");
            }
        }
    }

    private static String vector(io.euhedral_execution.benchmarks.cfd.config.Vector3 value) {
        return value.x() + " " + value.y() + " " + value.z();
    }

    static void export(Path directory, SimulationState state) throws IOException {
        var shape = state.shape();
        double dx = state.physics().voxelWidth(),
                time = state.completedSteps() * state.physics().timeStep();
        double[] scratch = new double[5];
        try (var out = Files.newBufferedWriter(directory.resolve("snapshot-" + state.completedSteps() + ".tsv"))) {
            out.write(Snapshot.HEADER);
            out.newLine();
            for (int z = 0; z < shape.nz(); z++)
                for (int y = 0; y < shape.ny(); y++)
                    for (int x = 0; x < shape.nx(); x++) {
                        int id = state.geometry().obstacleId(x + shape.nx() * (y + shape.ny() * z));
                        if (id == 0) state.readField(x, y, z, scratch, true);
                        else java.util.Arrays.fill(scratch, 0);
                        out.write((x + .5) * dx + "\t" + (y + .5) * dx + "\t" + (z + .5) * dx + "\t" + time + "\t"
                                + (id == 0 ? 0 : 1) + "\t" + id + "\t" + scratch[1] + "\t" + scratch[2] + "\t"
                                + scratch[3]
                                + "\t" + scratch[0] + "\t" + scratch[4] + "\n");
                    }
        }
        var flow = state.flowDiagnostics();
        try (var out = Files.newBufferedWriter(directory.resolve("forces-" + state.completedSteps() + ".tsv"))) {
            out.write("id\tfx\tfy\tfz\n");
            for (int slot = 0; slot < flow.obstacleCount(); slot++) {
                int id = flow.obstacleId(slot);
                out.write(Integer.toString(id));
                for (int axis = 0; axis < 3; axis++)
                    out.write("\t" + state.physics().units().forceToPhysical(flow.force(id, axis)));
                out.newLine();
            }
        }
    }
}
