package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration.CfdPhysics;
import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;

/// The driver owns generation boundaries; public reads expose values without mutable buffer access.
public final class SimulationState {
    private final PopulationGrid grid;
    private final CfdPhysics physics;
    private final GeometryMask geometry;
    private FlowDiagnostics flowDiagnostics;
    private int currentBuffer;
    private long completedSteps;
    private FieldExtractor.Diagnostics diagnostics;

    SimulationState(PopulationGrid grid, CfdPhysics physics, GeometryMask geometry, FlowDiagnostics flowDiagnostics) {
        this.grid = grid;
        this.physics = physics;
        this.geometry = geometry;
        this.flowDiagnostics = flowDiagnostics;
    }

    public FlowDiagnostics flowDiagnostics() {
        return flowDiagnostics;
    }

    public GridShape shape() {
        return grid.shape();
    }

    public long completedSteps() {
        return completedSteps;
    }

    public FieldExtractor.Diagnostics diagnostics() {
        return diagnostics;
    }

    public double population(int direction, int x, int y, int z) {
        return current()[direction][grid.index(x, y, z)];
    }

    public FieldExtractor.Field field(int x, int y, int z) {
        if (geometry.isSolid(grid.index(x, y, z)))
            throw new IllegalArgumentException("macroscopic fields are undefined in solid cells");
        return FieldExtractor.sample(
                current(), shape(), physics.densityReference(), completedSteps, x, y, z, physics.acceleration());
    }

    public GeometryMask geometry() {
        return geometry;
    }

    public FieldExtractor.Field physicalField(int x, int y, int z) {
        if (!physics.physicalUnits()) throw new IllegalStateException("physical fields require physical parameters");
        var field = field(x, y, z);
        var units = physics.units();
        return new FieldExtractor.Field(
                units.densityToPhysical(field.density()),
                units.velocityToPhysical(field.ux()),
                units.velocityToPhysical(field.uy()),
                units.velocityToPhysical(field.uz()),
                units.pressureToPhysical(field.gaugePressure()));
    }

    double[][] current() {
        return grid.buffer(currentBuffer);
    }

    double[][] next() {
        return grid.buffer(1 - currentBuffer);
    }

    void initialized(FieldExtractor.Diagnostics initial) {
        diagnostics = initial;
    }

    /// Called only after successful computation, diagnostics, and deadline checks on this thread.
    void complete(long step, FieldExtractor.Diagnostics completed, FlowDiagnostics flow) {
        flowDiagnostics = flow;
        currentBuffer = 1 - currentBuffer;
        completedSteps = step;
        if (completed != null) diagnostics = completed;
    }
}
