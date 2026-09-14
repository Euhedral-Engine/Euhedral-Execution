package io.euhedral_execution.benchmarks.cfd.solver;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration.CfdPhysics;
import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.FaceCondition;

/// Immutable setup shared by all ranges. Face indices are X-/X+/Y-/Y+/Z-/Z+.
/// See NUMERICS.md for the Hecht/Harting index mapping and population ownership.
public final class OpenBoundaries {
    private static final int[][] INCOMING = {
        {1, 7, 9, 11, 13}, {2, 8, 10, 12, 14},
        {3, 7, 10, 15, 17}, {4, 8, 9, 16, 18},
        {5, 11, 14, 15, 18}, {6, 12, 13, 16, 17}
    };
    private final int axis, inletFace;
    private final double ux, uy, uz, outletDensity, rampSteps;

    private OpenBoundaries(
            int axis, int inletFace, double ux, double uy, double uz, double outletDensity, double rampSteps) {
        this.axis = axis;
        this.inletFace = inletFace;
        this.ux = ux;
        this.uy = uy;
        this.uz = uz;
        this.outletDensity = outletDensity;
        this.rampSteps = rampSteps;
    }

    public static OpenBoundaries resolve(CfdConfiguration configuration) {
        return resolve(configuration.config(), configuration.physics());
    }

    public static OpenBoundaries resolve(SimulationConfig config, CfdPhysics physics) {
        var faces = config.geometry().faces();
        int axis = faces.openAxis();
        if (axis < 0) return null;
        if (physics.acceleration().magnitude() != 0)
            throw new IllegalArgumentException("open boundaries require zero body acceleration");
        if (physics.shear() != null)
            throw new IllegalArgumentException("open boundaries do not support shear initialization");
        var input = config.geometry().openBoundary();
        var velocity = physics.units().velocityToLattice(input.velocity());
        int inlet = faces.at(2 * axis) == FaceCondition.VELOCITY_INLET ? 2 * axis : 2 * axis + 1;
        double normal = axis == 0 ? velocity.x() : axis == 1 ? velocity.y() : velocity.z();
        if (normal * inwardSign(inlet) <= 0
                || velocity.magnitude() * Math.sqrt(3)
                        > config.physics().guards().maxMach()
                || Math.abs(normal) >= 1)
            throw new IllegalArgumentException(
                    "inlet velocity must point inward and satisfy the configured Mach guard");
        double density = input.outletDensity() == null
                ? physics.densityReference()
                : physics.units().densityToLattice(input.outletDensity());
        double ramp = physics.units().timeToLattice(input.rampTime());
        if (!Double.isFinite(density)
                || density <= 0
                || !Double.isFinite(ramp)
                || Math.abs(density / physics.densityReference() - 1)
                        > config.physics().guards().maxRelativeDensityVariation())
            throw new IllegalArgumentException("invalid converted outlet density or inlet ramp duration");
        return new OpenBoundaries(axis, inlet, velocity.x(), velocity.y(), velocity.z(), density, ramp);
    }

    public int axis() {
        return axis;
    }

    public int inletFace() {
        return inletFace;
    }

    public double outletDensity() {
        return outletDensity;
    }

    public double rampSteps() {
        return rampSteps;
    }

    public double velocity(int axis) {
        return axis == 0 ? ux : axis == 1 ? uy : uz;
    }

    public int face(int x, int y, int z, GridShape shape) {
        int coordinate = axis == 0 ? x : axis == 1 ? y : z;
        int size = axis == 0 ? shape.nx() : axis == 1 ? shape.ny() : shape.nz();
        return coordinate == 0 ? 2 * axis : coordinate == size - 1 ? 2 * axis + 1 : -1;
    }

    public boolean isInlet(int face) {
        return face == inletFace;
    }

    public double ramp(long step) {
        return rampSteps == 0 ? 1 : Math.min(1, step / rampSteps);
    }

    public static int incoming(int face, int slot) {
        return INCOMING[face][slot];
    }

    public static int inwardSign(int face) {
        return (face & 1) == 0 ? 1 : -1;
    }

    public static int component(int direction, int axis) {
        return axis == 0 ? D3Q19.x(direction) : axis == 1 ? D3Q19.y(direction) : D3Q19.z(direction);
    }

    public static boolean missing(int face, int direction) {
        return face >= 0 && component(direction, face / 2) == inwardSign(face);
    }

    public void reconstruct(double[] f, int face, long step) {
        double scale = ramp(step);
        reconstruct(
                f,
                face,
                isInlet(face) ? 0 : outletDensity,
                isInlet(face) ? scale * ux : 0,
                isInlet(face) ? scale * uy : 0,
                isInlet(face) ? scale * uz : 0);
    }

    /// Density zero selects prescribed velocity; positive density selects pressure with the
    /// supplied tangential velocity. Only the five missing populations are modified.
    public static void reconstruct(double[] f, int face, double density, double ux, double uy, double uz) {
        int axis = face / 2, sign = inwardSign(face);
        double known = 0, tx = 0, ty = 0, tz = 0;
        for (int i = 0; i < D3Q19.Q; i++) {
            int normal = component(i, axis) * sign;
            if (normal == 0) {
                known += f[i];
                tx += D3Q19.x(i) * f[i];
                ty += D3Q19.y(i) * f[i];
                tz += D3Q19.z(i) * f[i];
            } else if (normal < 0) known += 2 * f[i];
        }
        double un = axis == 0 ? ux : axis == 1 ? uy : uz;
        if (density == 0) density = known / (1 - sign * un);
        else {
            un = sign * (1 - known / density);
            if (axis == 0) ux = un;
            else if (axis == 1) uy = un;
            else uz = un;
        }
        tx = axis == 0 ? 0 : tx / 2 - density * ux / 3;
        ty = axis == 1 ? 0 : ty / 2 - density * uy / 3;
        tz = axis == 2 ? 0 : tz / 2 - density * uz / 3;
        for (int slot = 0; slot < 5; slot++) {
            int i = incoming(face, slot);
            f[i] = f[D3Q19.opposite(i)]
                    + 6 * D3Q19.weight(i) * density * (D3Q19.x(i) * ux + D3Q19.y(i) * uy + D3Q19.z(i) * uz)
                    - D3Q19.x(i) * tx
                    - D3Q19.y(i) * ty
                    - D3Q19.z(i) * tz;
        }
    }
}
