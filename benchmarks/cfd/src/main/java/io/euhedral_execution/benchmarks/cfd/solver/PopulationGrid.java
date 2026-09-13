package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.config.MemoryEstimate;

/// Two disjoint sets of post-collision populations, owned by one simulation driver.
public final class PopulationGrid {
    private final GridShape shape;
    private final double[][] first;
    private final double[][] second;

    public PopulationGrid(CfdConfiguration configuration) {
        shape = configuration.config().grid();
        MemoryEstimate.estimate(configuration.config(), configuration.memory().budgetBytes())
                .requireAllocatable(shape);
        int cells = Math.toIntExact(shape.cellCount());
        first = new double[D3Q19.Q][cells];
        second = new double[D3Q19.Q][cells];
    }

    public GridShape shape() {
        return shape;
    }

    /// Package ownership keeps mutable buffers inside the numerical implementation.
    double[][] buffer(int index) {
        return index == 0 ? first : second;
    }

    public int index(int x, int y, int z) {
        if (x < 0 || x >= shape.nx() || y < 0 || y >= shape.ny() || z < 0 || z >= shape.nz())
            throw new IndexOutOfBoundsException("cell outside grid " + shape);
        return x + shape.nx() * (y + shape.ny() * z);
    }
}
