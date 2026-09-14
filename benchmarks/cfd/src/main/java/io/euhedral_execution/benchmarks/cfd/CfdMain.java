package io.euhedral_execution.benchmarks.cfd;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.output.RunOutput;
import io.euhedral_execution.benchmarks.cfd.solver.OpenBoundaries;
import io.euhedral_execution.benchmarks.cfd.solver.SerialSimulation;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
            out.println("       [--steps N | --duration SECONDS] [--output DIRECTORY] [--export-every N]");
            out.println("       [--format appended|ascii] [--set path=JSON-value ...]");
            out.println("       euhedral-cfd --help");
            out.println("Inspect configuration or simulate D3Q19 flow with stationary solids, open boundaries and body"
                    + " forcing.");
            return 0;
        }
        try {
            boolean simulate = args[0].equals("simulate");
            if (!simulate && !args[0].equals("inspect"))
                throw new IllegalArgumentException("unknown command: " + args[0]);
            String config = null, backend = null;
            var overrides = new ArrayList<String>();
            var options = new HashSet<String>();
            for (int i = 1; i < args.length; i += 2) {
                if (i + 1 >= args.length || args[i + 1].isBlank())
                    throw new IllegalArgumentException("missing value for " + args[i]);
                String option = args[i], value = args[i + 1];
                if (!option.equals("--set") && !options.add(option))
                    throw new IllegalArgumentException("duplicate option: " + option);
                if (option.equals("--config")) config = value;
                else if (simulate && option.equals("--backend")) backend = value;
                else if (option.equals("--set")) overrides.add(value);
                else if (simulate) {
                    switch (option) {
                        case "--steps" -> overrides.add("execution.steps=" + value);
                        case "--duration" -> overrides.add("execution.durationSeconds=" + value);
                        case "--output" ->
                            overrides.add("output.directory="
                                    + ConfigLoader.json(Path.of(value)
                                            .toAbsolutePath()
                                            .normalize()
                                            .toString()));
                        case "--export-every" -> overrides.add("output.exportEverySteps=" + value);
                        case "--format" -> {
                            if (!value.equals("ascii") && !value.equals("appended"))
                                throw new IllegalArgumentException("format must be ascii or appended");
                            overrides.add("output.format=" + ConfigLoader.json(value.toUpperCase(Locale.ROOT)));
                        }
                        default -> throw new IllegalArgumentException("unknown option: " + option);
                    }
                } else throw new IllegalArgumentException("unknown option: " + option);
            }
            if (config == null) throw new IllegalArgumentException("--config is required");
            if (backend != null && !backend.equals("serial"))
                throw new IllegalArgumentException("only --backend serial is supported");
            CfdConfiguration configuration = ConfigLoader.load(Path.of(config), overrides);
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
        try (var artifacts = new RunOutput(configuration)) {
            out.println("Run directory: " + artifacts.directory());
            Thread owner = Thread.currentThread();
            Thread shutdown = new Thread(
                    () -> {
                        owner.interrupt();
                        try {
                            owner.join(5000);
                            artifacts.finish(RunOutput.Status.INTERRUPTED, null);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (IOException e) {
                            err.println("Could not persist interruption status: " + e.getMessage());
                        }
                    },
                    "cfd-run-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            ControlPlaneLattice lattice = null;
            long started = System.nanoTime();
            try {
                lattice = ControlPlaneLattice.getOrCreate();
                try (var simulation = new SerialSimulation(configuration, lattice)) {
                    artifacts.record(simulation.state(), System.nanoTime() - started);
                    while (simulation.state().completedSteps() < configuration.steps()) {
                        started = System.nanoTime();
                        simulation.step();
                        artifacts.record(simulation.state(), System.nanoTime() - started);
                    }
                    artifacts.finish(RunOutput.Status.COMPLETED, null);
                    var state = simulation.state();
                    var diagnostics = state.diagnostics();
                    out.println("Simulation completed: backend=serial, grid=" + state.shape());
                    out.println("Fluid cells: " + state.geometry().fluidCells());
                    out.println("Completed steps: " + state.completedSteps());
                    out.println("Completed lattice time: " + state.completedSteps());
                    if (configuration.physics().physicalUnits())
                        out.println("Completed physical time: "
                                + state.completedSteps()
                                        * configuration.physics().timeStep() + " s");
                    out.printf(
                            Locale.ROOT,
                            "Mass: %.12g%nDensity range: [%.12g, %.12g]%nMaximum speed (lattice): %.12g%nMaximum Mach: %.12g%n",
                            diagnostics.mass(),
                            diagnostics.minDensity(),
                            diagnostics.maxDensity(),
                            diagnostics.maxSpeed(),
                            diagnostics.maxMach());
                    printBoundaries(configuration, out);
                    var flow = state.flowDiagnostics();
                    out.printf(
                            Locale.ROOT,
                            "Flow diagnostics step: %d%nMass change (lattice): %.12g%n"
                                    + "Boundary-update inlet flux (lattice mass/step): %.12g%n"
                                    + "Boundary-update outlet flux (lattice mass/step): %.12g%nMass balance residual: %.12g%n"
                                    + "Estimated macroscopic inlet flux (lattice mass/step): %.12g%n"
                                    + "Estimated macroscopic outlet flux (lattice mass/step): %.12g%n",
                            flow.step(),
                            flow.massChange(),
                            flow.inletFlux(),
                            flow.outletFlux(),
                            flow.massBalanceResidual(),
                            flow.macroscopicInletFlux(),
                            flow.macroscopicOutletFlux());
                    for (int slot = 0; slot < flow.obstacleCount(); slot++) {
                        int id = flow.obstacleId(slot);
                        out.printf(
                                Locale.ROOT,
                                "Obstacle %d force (lattice): [%.12g, %.12g, %.12g]%n",
                                id,
                                flow.force(id, 0),
                                flow.force(id, 1),
                                flow.force(id, 2));
                        if (configuration.physics().physicalUnits()) {
                            var units = configuration.physics().units();
                            out.printf(
                                    Locale.ROOT,
                                    "Obstacle %d force (N): [%.12g, %.12g, %.12g]%n",
                                    id,
                                    units.forceToPhysical(flow.force(id, 0)),
                                    units.forceToPhysical(flow.force(id, 1)),
                                    units.forceToPhysical(flow.force(id, 2)));
                        }
                        if (flow.hasDragReference())
                            out.printf(Locale.ROOT, "Obstacle %d Cd: %.12g%n", id, flow.dragCoefficient(id));
                    }
                    out.println("Finite fields and positive density: true");
                    return 0;
                }
            } catch (IOException | RuntimeException error) {
                boolean interrupted = owner.isInterrupted() || error instanceof InterruptedIOException;
                artifacts.finish(interrupted ? RunOutput.Status.INTERRUPTED : RunOutput.Status.FAILED, error);
                err.println((interrupted ? "Simulation interrupted: " : "Simulation failed: ") + error.getMessage());
                return interrupted
                        ? 130
                        : error instanceof IOException ? 4 : error instanceof IllegalArgumentException ? 2 : 3;
            } finally {
                try {
                    if (lattice != null) lattice.close();
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdown);
                    } catch (IllegalStateException ignored) {
                        /// Shutdown is already in progress.
                    }
                }
            }
        } catch (IOException error) {
            err.println("Output error: " + error.getMessage());
            return 4;
        }
    }

    private static void printBoundaries(CfdConfiguration configuration, PrintStream out) {
        var boundaries = OpenBoundaries.resolve(configuration);
        if (boundaries != null) {
            out.println("Open boundary convention: on-site Hecht/Harting; transverse wall perimeter cells are solid");
            out.println("Inlet face index (X-/X+/Y-/Y+/Z-/Z+): " + boundaries.inletFace());
            out.printf(
                    Locale.ROOT,
                    "Lattice inlet velocity: [%.12g, %.12g, %.12g]%n"
                            + "Outlet lattice density: %.12g%nOutlet lattice gauge pressure: %.12g%n"
                            + "Inlet linear ramp duration (lattice steps): %.12g%n",
                    boundaries.velocity(0),
                    boundaries.velocity(1),
                    boundaries.velocity(2),
                    boundaries.outletDensity(),
                    (boundaries.outletDensity() - configuration.physics().densityReference()) / 3,
                    boundaries.rampSteps());
            if (configuration.physics().physicalUnits())
                out.println("Inlet ramp duration (s): "
                        + configuration.config().geometry().openBoundary().rampTime());
        }
        var reference = configuration.config().physics().forceReference();
        if (reference != null) {
            double magnitude = reference.direction().magnitude();
            out.printf(
                    Locale.ROOT,
                    "Drag reference (%s): speed=%.12g, area=%.12g, density=%.12g, unit direction=[%.12g, %.12g, %.12g]%n",
                    configuration.physics().physicalUnits() ? "m/s, m^2, kg/m^3" : "lattice units",
                    reference.velocity(),
                    reference.area(),
                    reference.density(),
                    reference.direction().x() / magnitude,
                    reference.direction().y() / magnitude,
                    reference.direction().z() / magnitude);
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
        out.println("Physical units per lattice unit: " + physics.units());
        out.println("Guards: " + resolved.config().physics().guards());
        out.println("Diagnostics interval: " + resolved.config().execution().diagnosticsEverySteps()
                + " (0 = initial/final only)");
        out.println("Steps: " + resolved.steps());
        out.println("Step deadline: " + resolved.config().execution().stepDeadlineMillis() + " ms");
        out.println("Faces: " + resolved.config().geometry().faces());
        printBoundaries(resolved, out);
        out.println("Geometry: " + resolved.config().geometry());
        out.println("Brick: " + resolved.config().execution().brick());
        out.println("Population bytes (exact payload): " + memory.populationBytes());
        out.println("Auxiliary bytes (estimated): " + memory.auxiliaryBytes());
        out.println("Total bytes (estimated): " + memory.totalBytes());
        out.println("Memory budget bytes: " + memory.budgetBytes() + " ("
                + (resolved.config().memoryLimitBytes() == null ? "half available JVM heap" : "explicit limit") + ")");
        out.println("Direction arrays indexable: " + memory.arrayIndexable());
        out.println("Output directory: " + resolved.outputDirectory());
        out.println("Field format: " + resolved.config().output().format());
        out.println("Export every steps: " + resolved.config().output().exportEverySteps() + " (0 disables export)");
        out.println("Inspection passed; no populations allocated or workers started.");
    }
}
