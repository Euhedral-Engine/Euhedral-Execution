package io.euhedral_execution.benchmarks.cfd;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.solver.SerialSimulation;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;

public final class CfdMain {
    private CfdMain() {}

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0
                || (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h")))
                || (args.length == 2
                        && (args[0].equals("inspect") || args[0].equals("simulate"))
                        && args[1].equals("--help"))) {
            out.println("Usage: euhedral-cfd inspect --config <file.json>");
            out.println("       euhedral-cfd simulate --config <file.json> [--backend serial]");
            out.println("       euhedral-cfd --help");
            out.println("Inspect configuration or simulate unforced periodic D3Q19 flow.");
            return 0;
        }
        try {
            boolean simulate = args[0].equals("simulate");
            if (!simulate && !args[0].equals("inspect"))
                throw new IllegalArgumentException("unknown command: " + args[0]);
            String config = null, backend = null;
            for (int i = 1; i < args.length; i += 2) {
                if (i + 1 >= args.length || args[i + 1].isBlank())
                    throw new IllegalArgumentException("missing value for " + args[i]);
                if (args[i].equals("--config") && config == null) config = args[i + 1];
                else if (simulate && args[i].equals("--backend") && backend == null) backend = args[i + 1];
                else throw new IllegalArgumentException("unknown or duplicate option: " + args[i]);
            }
            if (config == null) throw new IllegalArgumentException("--config is required");
            if (backend != null && !backend.equals("serial"))
                throw new IllegalArgumentException("only --backend serial is supported");
            CfdConfiguration configuration = ConfigLoader.load(Path.of(config));
            if (simulate) return simulate(configuration, out, err);
            print(configuration, out);
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            err.println("Configuration/usage error: " + e.getMessage());
            return 2;
        } catch (SimulationException e) {
            err.println("Simulation failed during initialization: " + e.getMessage());
            return 3;
        }
    }

    private static int simulate(CfdConfiguration configuration, PrintStream out, PrintStream err) {
        SerialSimulation.requireSupported(configuration);
        var lattice = ControlPlaneLattice.getOrCreate();
        try (var simulation = new SerialSimulation(configuration, lattice)) {
            try {
                simulation.run();
            } catch (SimulationException e) {
                err.println("Simulation failed: " + e.getMessage());
                err.println("Last completed step: " + simulation.state().completedSteps());
                return 3;
            }
            var state = simulation.state();
            var diagnostics = state.diagnostics();
            out.println("Simulation completed: backend=serial, grid=" + state.shape());
            out.println("Completed steps: " + state.completedSteps());
            out.println("Completed lattice time: " + state.completedSteps());
            if (configuration.physics().physicalUnits())
                out.println("Completed physical time: "
                        + state.completedSteps() * configuration.physics().timeStep() + " s");
            out.printf(
                    Locale.ROOT,
                    "Mass: %.12g%nDensity range: [%.12g, %.12g]%nMaximum speed (lattice): %.12g%nMaximum Mach: %.12g%n",
                    diagnostics.mass(),
                    diagnostics.minDensity(),
                    diagnostics.maxDensity(),
                    diagnostics.maxSpeed(),
                    diagnostics.maxMach());
            out.println("Finite fields and positive density: true");
            return 0;
        } finally {
            lattice.close();
        }
    }

    private static void print(CfdConfiguration resolved, PrintStream out) {
        var physics = resolved.physics();
        var memory = resolved.memory();
        out.println("Configuration: " + resolved.configFile());
        out.println("Schema version: " + resolved.config().schemaVersion());
        out.println("Grid: " + resolved.config().grid());
        out.println("Cell count: " + resolved.config().grid().cellCount());
        out.println("Parameter mode: " + (physics.physicalUnits() ? "physical" : "lattice"));
        out.printf(
                Locale.ROOT,
                "Lattice density reference: %.12g%nLattice viscosity: %.12g%nTau: %.12g%n",
                physics.densityReference(),
                physics.viscosity(),
                physics.tau());
        out.println("Lattice initial velocity: " + physics.initialVelocity());
        out.println("Lattice acceleration: " + physics.acceleration());
        if (physics.shear() != null) out.println("Lattice shear profile: " + physics.shear());
        out.printf(Locale.ROOT, "Initial Mach: %.12g (low-Mach target <= 0.1)%n", physics.initialMach());
        out.println("Initial Reynolds: "
                + (physics.reynolds() == null ? "n/a (referenceLength omitted)" : physics.reynolds()));
        if (physics.physicalUnits()) {
            out.printf(
                    Locale.ROOT,
                    "Voxel width: %.12g m%nTime step: %.12g s%nDensity scale: %.12g kg/m^3%n",
                    physics.voxelWidth(),
                    physics.timeStep(),
                    physics.densityScale());
            out.println("Resolved physical duration: " + resolved.physicalDurationSeconds() + " s");
        }
        out.println("Steps: " + resolved.steps());
        out.println("Step deadline: " + resolved.config().execution().stepDeadlineMillis() + " ms");
        out.println("Faces: " + resolved.config().geometry().faces());
        out.println("Brick: " + resolved.config().execution().brick());
        out.println("Population bytes (exact payload): " + memory.populationBytes());
        out.println("Auxiliary bytes (estimated): " + memory.auxiliaryBytes());
        out.println("Total bytes (estimated): " + memory.totalBytes());
        out.println("Memory budget bytes: " + memory.budgetBytes() + " ("
                + (resolved.config().memoryLimitBytes() == null ? "half available JVM heap" : "explicit limit") + ")");
        out.println("Direction arrays indexable: " + memory.arrayIndexable());
        out.println("Output directory: " + resolved.outputDirectory());
        out.println("Export every steps: " + resolved.config().output().exportEverySteps() + " (0 disables export)");
        out.println("Inspection passed; no populations allocated or workers started.");
    }
}
