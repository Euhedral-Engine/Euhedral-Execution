package io.euhedral_execution.benchmarks.cfd.frames;

import io.euhedral_execution.benchmarks.cfd.solver.D3Q19;
import io.euhedral_execution.benchmarks.cfd.solver.OpenBoundaries;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;
import io.euhedral_execution.core.impl.FrameManager;
import java.util.Arrays;
import java.util.Objects;

/// Reusable pull/collide work for one half-open destination range.
/// Disjoint ranges may share a generation context. The driver consumes every terminal result
/// before diagnostics, buffer swaps, or replacement. A pooled frame must be reacquired from its
/// manager before replacement; its scratch remains private for the frame's entire lifetime.
public final class CfdRangeFrame extends CfdFrame {
    private static final double[] NO_FORCES = new double[0];
    private int xFrom, xTo, yFrom, yTo, zFrom, zTo;
    private final double[] incoming = new double[D3Q19.Q];
    private StepContext context;
    private int rangeId;
    private double[] forces = NO_FORCES;
    private double massChange, inletFlux, outletFlux, macroscopicInletFlux, macroscopicOutletFlux;

    public int rangeId() {
        return rangeId;
    }

    public long step() {
        return context.step();
    }

    public int forceCount() {
        return context.geometry().forceCount();
    }

    public int forceId(int slot) {
        return context.geometry().forceId(slot);
    }

    public double force(int slot, int axis) {
        requireSuccess();
        if (slot < 0 || slot >= forceCount() || axis < 0 || axis > 2)
            throw new IllegalArgumentException("invalid force slot or axis");
        return forces[3 * slot + axis];
    }

    public double massChange() {
        requireSuccess();
        return massChange;
    }

    public double inletFlux() {
        requireSuccess();
        return inletFlux;
    }

    public double outletFlux() {
        requireSuccess();
        return outletFlux;
    }

    public double macroscopicInletFlux() {
        requireSuccess();
        return macroscopicInletFlux;
    }

    public double macroscopicOutletFlux() {
        requireSuccess();
        return macroscopicOutletFlux;
    }

    public CfdRangeFrame(long idHash, FrameManager<?, ?> recycler) {
        super(idHash, recycler);
    }

    /// Fixed range storage is allocated once; each generation replaces only the shared context.
    public CfdRangeFrame(long idHash, int rangeId, int xFrom, int xTo, int yFrom, int yTo, int zFrom, int zTo) {
        this(idHash, null);
        if (rangeId < 0 || xFrom < 0 || yFrom < 0 || zFrom < 0 || xTo <= xFrom || yTo <= yFrom || zTo <= zFrom) {
            throw new IllegalArgumentException("invalid range ordinal or bounds");
        }
        this.rangeId = rangeId;
        this.xFrom = xFrom;
        this.xTo = xTo;
        this.yFrom = yFrom;
        this.yTo = yTo;
        this.zFrom = zFrom;
        this.zTo = zTo;
    }

    public int xFrom() {
        return xFrom;
    }

    public int xTo() {
        return xTo;
    }

    public int yFrom() {
        return yFrom;
    }

    public int yTo() {
        return yTo;
    }

    public int zFrom() {
        return zFrom;
    }

    public int zTo() {
        return zTo;
    }

    public void reserveForceStorage(int forceCount) {
        if (status() != Status.NEW || forceCount < 0) {
            throw new IllegalStateException("reserve force storage during setup only");
        }
        forces = forceCount == 0 ? NO_FORCES : new double[Math.multiplyExact(3, forceCount)];
    }

    public void replace(StepContext context) {
        replace(context, rangeId, xFrom, xTo, yFrom, yTo, zFrom, zTo);
    }

    /// Replaces all work inputs outside execution, including when called by a `FrameFactory`.
    /// Bounds restrict destination writes only; pull reads may cross range boundaries.
    public void replace(StepContext context, int xFrom, int xTo, int yFrom, int yTo, int zFrom, int zTo) {
        replace(context, 0, xFrom, xTo, yFrom, yTo, zFrom, zTo);
    }

    public void replace(StepContext context, int rangeId, int xFrom, int xTo, int yFrom, int yTo, int zFrom, int zTo) {
        Objects.requireNonNull(context);
        if (rangeId < 0) throw new IllegalArgumentException("range ID must be non-negative");
        if (xFrom < 0 || yFrom < 0 || zFrom < 0 || xTo <= xFrom || yTo <= yFrom || zTo <= zFrom)
            throw new IllegalArgumentException("cell range must have positive extents and non-negative origins");
        var shape = context.shape();
        if (xTo > shape.nx() || yTo > shape.ny() || zTo > shape.nz())
            throw new IllegalArgumentException("cell range exceeds grid " + shape);
        int required = Math.multiplyExact(3, context.geometry().forceCount());
        beginPreparation();
        this.context = context;
        this.rangeId = rangeId;
        /// Capacity changes only when replacing the geometry, never for another timestep.
        if (forces.length < required) forces = new double[required];
        Arrays.fill(forces, 0);
        massChange = inletFlux = outletFlux = macroscopicInletFlux = macroscopicOutletFlux = 0;
        this.xFrom = xFrom;
        this.xTo = xTo;
        this.yFrom = yFrom;
        this.yTo = yTo;
        this.zFrom = zFrom;
        this.zTo = zTo;
        ready();
    }

    @Override
    protected void executeBody() {
        int nx = context.shape().nx(),
                ny = context.shape().ny(),
                nz = context.shape().nz();
        double[][] current = context.current(), next = context.next();
        var geometry = context.geometry();
        var boundaries = geometry.openBoundaries();
        double massCorrection = 0;
        var acceleration = context.acceleration();
        double ax = acceleration.x(), ay = acceleration.y(), az = acceleration.z();
        boolean forced = ax != 0 || ay != 0 || az != 0;
        int untilProgressCheck = 0;
        for (int z = zFrom; z < zTo; z++) {
            for (int y = yFrom; y < yTo; y++) {
                for (int x = xFrom; x < xTo; x++) {
                    if (untilProgressCheck-- == 0) {
                        if (!isAlive()) {
                            throwCancelSignal();
                        }
                        context.checkProgress(x, y, z);
                        untilProgressCheck = 255;
                    }
                    int destination = x + nx * (y + ny * z);
                    if (geometry.isSolid(destination)) continue;
                    int face = boundaries == null ? -1 : boundaries.face(x, y, z, context.shape());
                    for (int i = 0; i < D3Q19.Q; i++) {
                        /// Open-face reconstruction owns all five inward populations, including
                        /// diagonals beyond a solid perimeter. Solid destinations never enter here.
                        if (OpenBoundaries.missing(face, i)) {
                            incoming[i] = Double.NaN;
                            continue;
                        }
                        int sx = x - D3Q19.x(i), sy = y - D3Q19.y(i), sz = z - D3Q19.z(i);
                        int solid = geometry.wallId(sx, sy, sz);
                        int source = wrap(sx, nx) + nx * (wrap(sy, ny) + ny * wrap(sz, nz));
                        if (solid == 0) solid = geometry.obstacleId(source);
                        double f = solid != 0 ? current[D3Q19.opposite(i)][destination] : current[i][source];
                        incoming[i] = f;
                        if (solid > 0) {
                            int slot = 3 * geometry.forceSlot(solid);
                            forces[slot] -= 2 * D3Q19.x(i) * f;
                            forces[slot + 1] -= 2 * D3Q19.y(i) * f;
                            forces[slot + 2] -= 2 * D3Q19.z(i) * f;
                        }
                    }
                    if (face >= 0) {
                        boundaries.reconstruct(incoming, face, context.step());
                        double flux = 0;
                        for (int slot = 0; slot < 5; slot++) {
                            int i = OpenBoundaries.incoming(face, slot);
                            flux += incoming[i] - current[D3Q19.opposite(i)][destination];
                        }
                        if (boundaries.isInlet(face)) inletFlux += flux;
                        else outletFlux -= flux;
                    }
                    double rho = 0, mx = 0, my = 0, mz = 0, oldRho = 0;
                    for (int i = 0; i < D3Q19.Q; i++) {
                        double f = incoming[i];
                        if (!Double.isFinite(f))
                            throw new SimulationException(
                                    context.step(), x, y, z, "non-finite incoming population direction=" + i);
                        rho += f;
                        oldRho += current[i][destination];
                        mx += D3Q19.x(i) * f;
                        my += D3Q19.y(i) * f;
                        mz += D3Q19.z(i) * f;
                    }
                    if (!Double.isFinite(rho) || rho <= 0)
                        throw new SimulationException(context.step(), x, y, z, "density must be finite and positive");
                    double ux = mx / rho + ax / 2, uy = my / rho + ay / 2, uz = mz / rho + az / 2;
                    if (!Double.isFinite(ux) || !Double.isFinite(uy) || !Double.isFinite(uz))
                        throw new SimulationException(context.step(), x, y, z, "non-finite velocity");
                    context.checkFields(rho, ux, uy, uz, x, y, z);
                    if (face >= 0) {
                        double flux = (boundaries.axis() == 0 ? mx : boundaries.axis() == 1 ? my : mz)
                                * OpenBoundaries.inwardSign(face);
                        if (boundaries.isInlet(face)) macroscopicInletFlux += flux;
                        else macroscopicOutletFlux -= flux;
                    }
                    double nextRho = 0;
                    for (int i = 0; i < D3Q19.Q; i++) {
                        double value =
                                incoming[i] - context.omega() * (incoming[i] - D3Q19.equilibrium(i, rho, ux, uy, uz));
                        if (forced) value += (1 - context.omega() / 2) * D3Q19.guo(i, rho, ux, uy, uz, ax, ay, az);
                        if (!Double.isFinite(value))
                            throw new SimulationException(
                                    context.step(), x, y, z, "non-finite collision population direction=" + i);
                        next[i][destination] = value;
                        nextRho += value;
                    }
                    double term = (nextRho - oldRho) - massCorrection;
                    double sum = massChange + term;
                    massCorrection = (sum - massChange) - term;
                    massChange = sum;
                }
            }
        }
        if (!Double.isFinite(massChange)
                || !Double.isFinite(inletFlux)
                || !Double.isFinite(outletFlux)
                || !Double.isFinite(macroscopicInletFlux)
                || !Double.isFinite(macroscopicOutletFlux))
            throw new SimulationException(
                    context.step(), xTo - 1, yTo - 1, zTo - 1, "non-finite range mass or flux total");
        for (int slot = 0; slot < geometry.forceCount() * 3; slot++)
            if (!Double.isFinite(forces[slot]))
                throw new SimulationException(
                        context.step(), xTo - 1, yTo - 1, zTo - 1, "non-finite obstacle force total");
        if (!isAlive()) {
            throwCancelSignal();
        }
        context.checkProgress(xTo - 1, yTo - 1, zTo - 1);
    }

    @Override
    protected void publishSuccess() {
        /// Only acknowledge this range. Generation completion belongs to the driver.
    }

    @Override
    protected long generation() {
        return context.step();
    }

    private static int wrap(int coordinate, int size) {
        return coordinate < 0 ? size - 1 : coordinate == size ? 0 : coordinate;
    }
}
