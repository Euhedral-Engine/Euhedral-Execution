package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;

/// Disjoint batch slots preserve deterministic batch-ordinal reduction without retaining frames.
/// Workers write only their ordinal; the generation barrier publishes every slot to the driver.
public final class RangeResults {
    final double[][] values;
    final int[] ids;
    final int count;

    public RangeResults(int count, GeometryMask geometry) {
        this.count = count;
        ids = new int[geometry.forceCount()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = geometry.forceId(i);
        }
        values = new double[Math.addExact(5, Math.multiplyExact(3, ids.length))][count];
    }

    public void record(
            int ordinal,
            double mass,
            double inlet,
            double outlet,
            double macroInlet,
            double macroOutlet,
            double[] forces) {
        values[0][ordinal] = mass;
        values[1][ordinal] = inlet;
        values[2][ordinal] = outlet;
        values[3][ordinal] = macroInlet;
        values[4][ordinal] = macroOutlet;
        for (int i = 0; i < forces.length; i++) {
            values[5 + i][ordinal] = forces[i];
        }
    }
}
