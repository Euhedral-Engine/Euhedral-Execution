package io.euhedral_execution.benchmarks.cfd;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
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
                || (args.length == 2 && args[0].equals("inspect") && args[1].equals("--help"))) {
            out.println("Usage: euhedral-cfd inspect --config <file.json>");
            out.println("       euhedral-cfd --help");
            out.println("Inspect schema version 1, resolved units, and memory before population allocation.");
            return 0;
        }
        if (args.length != 3 || !args[0].equals("inspect") || !args[1].equals("--config") || args[2].isBlank()) {
            err.println("Usage error: expected inspect --config <file.json>; use --help.");
            return 2;
        }
        try {
            print(ConfigLoader.load(Path.of(args[2])), out);
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            err.println("Configuration error: " + e.getMessage());
            return 2;
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
