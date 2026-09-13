package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;

/// The driver owns generation boundaries; public reads expose values without mutable buffer access.
public final class SimulationState {
    private final PopulationGrid grid;
    private final double densityReference;
    private int currentBuffer;
    private long completedSteps;
    private FieldExtractor.Diagnostics diagnostics;

    SimulationState(PopulationGrid grid, double densityReference) {
        this.grid = grid;
        this.densityReference = densityReference;
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
        grid.index(x, y, z);
        return FieldExtractor.sample(current(), shape(), densityReference, completedSteps, x, y, z);
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
    void complete(FieldExtractor.Diagnostics completed) {
        currentBuffer = 1 - currentBuffer;
        completedSteps = completed.step();
        diagnostics = completed;
    }
}
