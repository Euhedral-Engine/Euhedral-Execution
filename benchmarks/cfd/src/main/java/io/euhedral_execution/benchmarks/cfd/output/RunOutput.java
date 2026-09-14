package io.euhedral_execution.benchmarks.cfd.output;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationState;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;

/// Driver-owned synchronous output. Each invocation creates its own directory beneath the base.
/// The shutdown hook may finish status after a bounded wait; population and CSV access stay on the driver.
public final class RunOutput implements AutoCloseable {
    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        INTERRUPTED
    }

    private final CfdConfiguration configuration;
    private final Path directory;
    private final VtiWriter writer = new VtiWriter();
    private final PvdWriter collection;
    private final BufferedWriter metrics;
    private final StringBuilder row = new StringBuilder(1024);
    private volatile long completedStep, exportedStep = -1, simulationNs, exportNs;
    private boolean headerWritten;
    private volatile boolean finished;
    private long recordedStep = -1;

    public RunOutput(CfdConfiguration configuration) throws IOException {
        this.configuration = configuration;
        Files.createDirectories(configuration.outputDirectory());
        directory = Files.createTempDirectory(configuration.outputDirectory(), "run-");
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", configuration.configFile().toString());
        metadata.put("runDirectory", directory.toString());
        metadata.put("backend", "serial");
        metadata.put("timeUnit", configuration.physics().physicalUnits() ? "seconds" : "lattice_steps");
        metadata.put("fieldUnits", configuration.physics().physicalUnits() ? "SI" : "lattice");
        metadata.put("physics", configuration.physics());
        metadata.put("steps", configuration.steps());
        metadata.put("memory", configuration.memory());
        metadata.put("configuration", configuration.config());
        try {
            writeJson("configuration.json", configuration.config());
            writeJson("resolved.json", metadata);
            writeStatus(Status.RUNNING, null);
            collection = configuration.config().output().exportEverySteps() > 0
                    ? new PvdWriter(
                            directory.resolve("flow.pvd"),
                            configuration.physics().physicalUnits())
                    : null;
            metrics = Files.newBufferedWriter(directory.resolve("metrics.csv"));
        } catch (IOException | RuntimeException error) {
            try {
                writeStatus(Status.FAILED, error);
            } catch (IOException secondary) {
                error.addSuppressed(secondary);
            }
            throw error;
        }
    }

    public Path directory() {
        return directory;
    }

    public void record(SimulationState state, long elapsedSimulationNs) throws IOException {
        if (finished || elapsedSimulationNs < 0 || state.completedSteps() != recordedStep + 1)
            throw new IllegalArgumentException("record each completed step once, before finishing the run");
        completedStep = state.completedSteps();
        simulationNs = Math.addExact(simulationNs, elapsedSimulationNs);
        AtomicOutput.checkInterrupted();
        long elapsedExportNs = 0;
        long interval = configuration.config().output().exportEverySteps();
        if (collection != null
                && (completedStep == 0 || completedStep == configuration.steps() || completedStep % interval == 0)) {
            long started = System.nanoTime();
            Path file = directory.resolve(String.format(Locale.ROOT, "frame-%012d.vti", completedStep));
            try {
                writer.write(file, state, configuration);
                collection.append(
                        completedStep, completedStep * configuration.physics().timeStep(), file);
                exportedStep = completedStep;
            } finally {
                elapsedExportNs = System.nanoTime() - started;
                exportNs = Math.addExact(exportNs, elapsedExportNs);
            }
        }
        var flow = state.flowDiagnostics();
        if (!headerWritten) {
            metrics.write(
                    "step,time,time_unit,diagnostics_step,mass_lattice,min_density_lattice,max_density_lattice,max_speed_lattice,max_mach,"
                            + "mass_change_lattice,inlet_flux_lattice,outlet_flux_lattice,mass_balance_residual_lattice,"
                            + "estimated_inlet_flux_lattice,estimated_outlet_flux_lattice,simulation_ns,export_ns");
            for (int i = 0; i < flow.obstacleCount(); i++) {
                int id = flow.obstacleId(i);
                metrics.write(
                        ",force_x_" + id + "_lattice,force_y_" + id + "_lattice,force_z_" + id + "_lattice,cd_" + id);
            }
            metrics.newLine();
            headerWritten = true;
        }
        row.setLength(0);
        row.append(completedStep)
                .append(',')
                .append(completedStep * configuration.physics().timeStep())
                .append(',')
                .append(configuration.physics().physicalUnits() ? "seconds" : "lattice_steps")
                .append(',');
        var diagnostics = state.diagnostics();
        row.append(diagnostics.step());
        if (diagnostics.step() == completedStep) {
            row.append(',')
                    .append(diagnostics.mass())
                    .append(',')
                    .append(diagnostics.minDensity())
                    .append(',')
                    .append(diagnostics.maxDensity())
                    .append(',')
                    .append(diagnostics.maxSpeed())
                    .append(',')
                    .append(diagnostics.maxMach());
        } else row.append(",,,,,");
        row.append(',')
                .append(flow.massChange())
                .append(',')
                .append(flow.inletFlux())
                .append(',')
                .append(flow.outletFlux())
                .append(',')
                .append(flow.massBalanceResidual())
                .append(',')
                .append(flow.macroscopicInletFlux())
                .append(',')
                .append(flow.macroscopicOutletFlux())
                .append(',')
                .append(elapsedSimulationNs)
                .append(',')
                .append(elapsedExportNs);
        for (int i = 0; i < flow.obstacleCount(); i++) {
            int id = flow.obstacleId(i);
            for (int axis = 0; axis < 3; axis++) row.append(',').append(flow.force(id, axis));
            row.append(',');
            if (flow.hasDragReference()) row.append(flow.dragCoefficient(id));
        }
        row.append('\n');
        metrics.write(row.toString());
        metrics.flush();
        recordedStep = completedStep;
    }

    public synchronized void finish(Status status, Throwable error) throws IOException {
        if (finished) return;
        if (status == Status.COMPLETED && recordedStep != configuration.steps())
            throw new IllegalStateException("cannot complete before recording the final step");
        if (status == Status.RUNNING) throw new IllegalArgumentException("terminal status required");
        /// Persist interruption status without letting interrupted NIO abort the status write itself.
        boolean interrupted = Thread.interrupted();
        try {
            writeStatus(status, error);
            finished = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void writeStatus(Status status, Throwable error) throws IOException {
        var values = new LinkedHashMap<String, Object>();
        values.put("status", status);
        values.put("updatedAt", Instant.now().toString());
        values.put("completedSteps", completedStep);
        values.put("lastExportedStep", exportedStep);
        values.put("simulationNs", simulationNs);
        values.put("exportNs", exportNs);
        if (error != null) values.put("error", error.toString());
        writeJson("status.json", values);
    }

    private void writeJson(String name, Object value) throws IOException {
        String json = ConfigLoader.json(value);
        AtomicOutput.write(directory.resolve(name), temporary -> Files.writeString(temporary, json + "\n"));
    }

    @Override
    public void close() throws IOException {
        try {
            finish(Thread.currentThread().isInterrupted() ? Status.INTERRUPTED : Status.FAILED, null);
        } finally {
            metrics.close();
        }
    }
}
