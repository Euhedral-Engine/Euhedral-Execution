package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration.CfdPhysics;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.ForceReference;
import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import java.util.Arrays;

/// Driver-owned, reusable completed-step totals. Read before advancing the simulation again.
/// Range arrays must be in increasing stable ID order; completion is acquired before reading slots.
public final class FlowDiagnostics {
    private final int[] ids;
    private final double[] forces;
    private final double denominator, dx, dy, dz;
    private long step;
    private double massChange, inletFlux, outletFlux, macroscopicInletFlux, macroscopicOutletFlux;

    public FlowDiagnostics(GeometryMask geometry, ForceReference reference, CfdPhysics physics) {
        ids = new int[geometry.forceCount()];
        forces = new double[Math.multiplyExact(3, ids.length)];
        for (int i = 0; i < ids.length; i++) ids[i] = geometry.forceId(i);
        denominator = validateReference(reference, physics);
        double magnitude = reference == null ? 1 : reference.direction().magnitude();
        dx = reference == null ? 0 : reference.direction().x() / magnitude;
        dy = reference == null ? 0 : reference.direction().y() / magnitude;
        dz = reference == null ? 0 : reference.direction().z() / magnitude;
    }

    public static double validateReference(ForceReference reference, CfdPhysics physics) {
        if (reference == null) return Double.NaN;
        var units = physics.units();
        double speed = units.velocityToLattice(reference.velocity());
        double density = units.densityToLattice(reference.density());
        double area = units.lengthToLattice(units.lengthToLattice(reference.area()));
        double denominator = 0.5 * density * speed * speed * area;
        if (!Double.isFinite(denominator) || denominator <= 0)
            throw new IllegalArgumentException("converted drag reference denominator must be finite and positive");
        return denominator;
    }

    public void reduce(long step, CfdRangeFrame[] ranges) {
        if (step <= 0 || ranges.length == 0) throw new IllegalArgumentException("a completed generation needs ranges");
        int previous = -1;
        for (var range : ranges) {
            range.requireSuccess();
            if (range.step() != step || range.rangeId() <= previous || range.forceCount() != ids.length)
                throw new IllegalArgumentException("range generation, ordering or force slots differ");
            for (int i = 0; i < ids.length; i++)
                if (range.forceId(i) != ids[i]) throw new IllegalArgumentException("range obstacle IDs differ");
            previous = range.rangeId();
        }
        Arrays.fill(forces, 0);
        massChange = inletFlux = outletFlux = macroscopicInletFlux = macroscopicOutletFlux = 0;
        for (var range : ranges) {
            massChange += range.massChange();
            inletFlux += range.inletFlux();
            outletFlux += range.outletFlux();
            macroscopicInletFlux += range.macroscopicInletFlux();
            macroscopicOutletFlux += range.macroscopicOutletFlux();
            for (int i = 0; i < ids.length; i++)
                for (int axis = 0; axis < 3; axis++) forces[3 * i + axis] += range.force(i, axis);
        }
        if (!Double.isFinite(massChange)
                || !Double.isFinite(inletFlux)
                || !Double.isFinite(outletFlux)
                || !Double.isFinite(macroscopicInletFlux)
                || !Double.isFinite(macroscopicOutletFlux))
            throw new SimulationException(step, 0, 0, 0, "non-finite reduced flow diagnostics");
        for (int i = 0; i < ids.length; i++) {
            for (int axis = 0; axis < 3; axis++)
                if (!Double.isFinite(forces[3 * i + axis]))
                    throw new SimulationException(step, 0, 0, 0, "non-finite reduced obstacle force");
            if (hasDragReference() && !Double.isFinite(dragCoefficient(ids[i])))
                throw new SimulationException(step, 0, 0, 0, "non-finite drag coefficient");
        }
        this.step = step;
    }

    public long step() {
        return step;
    }

    public double massChange() {
        return massChange;
    }

    public double inletFlux() {
        return inletFlux;
    }

    public double outletFlux() {
        return outletFlux;
    }

    public double massBalanceResidual() {
        return massChange - (inletFlux - outletFlux);
    }

    public double macroscopicInletFlux() {
        return macroscopicInletFlux;
    }

    public double macroscopicOutletFlux() {
        return macroscopicOutletFlux;
    }

    public int obstacleCount() {
        return ids.length;
    }

    public int obstacleId(int slot) {
        return ids[slot];
    }

    public double force(int id, int axis) {
        int slot = Arrays.binarySearch(ids, id);
        if (slot < 0 || axis < 0 || axis > 2) throw new IllegalArgumentException("unknown obstacle or force axis");
        return forces[3 * slot + axis];
    }

    public boolean hasDragReference() {
        return !Double.isNaN(denominator);
    }

    public double dragCoefficient(int id) {
        if (!hasDragReference()) throw new IllegalStateException("drag coefficient requires physics.forceReference");
        return (force(id, 0) * dx + force(id, 1) * dy + force(id, 2) * dz) / denominator;
    }
}
