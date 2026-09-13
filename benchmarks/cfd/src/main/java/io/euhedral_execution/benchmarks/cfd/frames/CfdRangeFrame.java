package io.euhedral_execution.benchmarks.cfd.frames;

import io.euhedral_execution.benchmarks.cfd.solver.D3Q19;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;
import io.euhedral_execution.core.impl.FrameManager;
import java.util.Objects;

/// Reusable pull/collide work for one half-open destination range.
/// Disjoint ranges may share a generation context. The driver consumes every terminal result
/// before diagnostics, buffer swaps, or replacement. A pooled frame must be reacquired from its
/// manager before replacement; its scratch remains private for the frame's entire lifetime.
public final class CfdRangeFrame extends CfdFrame {
    private int xFrom, xTo, yFrom, yTo, zFrom, zTo;
    private final double[] incoming = new double[D3Q19.Q];
    private StepContext context;

    public CfdRangeFrame(long idHash, FrameManager<?, ?> recycler) {
        super(idHash, recycler);
    }

    /// Replaces all work inputs outside execution, including when called by a `FrameFactory`.
    /// Bounds restrict destination writes only; pull reads may cross range boundaries.
    public void replace(StepContext context, int xFrom, int xTo, int yFrom, int yTo, int zFrom, int zTo) {
        Objects.requireNonNull(context);
        if (xFrom < 0 || yFrom < 0 || zFrom < 0 || xTo <= xFrom || yTo <= yFrom || zTo <= zFrom)
            throw new IllegalArgumentException("cell range must have positive extents and non-negative origins");
        var shape = context.shape();
        if (xTo > shape.nx() || yTo > shape.ny() || zTo > shape.nz())
            throw new IllegalArgumentException("cell range exceeds grid " + shape);
        beginPreparation();
        this.context = context;
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
        for (int z = zFrom; z < zTo; z++) {
            for (int y = yFrom; y < yTo; y++) {
                for (int x = xFrom; x < xTo; x++) {
                    if ((x - xFrom) % 256 == 0) {
                        if (!isAlive()) throwCancelSignal();
                        context.checkProgress(x, y, z);
                    }
                    double rho = 0, mx = 0, my = 0, mz = 0;
                    for (int i = 0; i < D3Q19.Q; i++) {
                        int sx = wrap(x - D3Q19.x(i), nx);
                        int sy = wrap(y - D3Q19.y(i), ny);
                        int sz = wrap(z - D3Q19.z(i), nz);
                        double f = current[i][sx + nx * (sy + ny * sz)];
                        if (!Double.isFinite(f))
                            throw new SimulationException(
                                    context.step(), x, y, z, "non-finite incoming population direction=" + i);
                        incoming[i] = f;
                        rho += f;
                        mx += D3Q19.x(i) * f;
                        my += D3Q19.y(i) * f;
                        mz += D3Q19.z(i) * f;
                    }
                    if (!Double.isFinite(rho) || rho <= 0)
                        throw new SimulationException(context.step(), x, y, z, "density must be finite and positive");
                    double ux = mx / rho, uy = my / rho, uz = mz / rho;
                    if (!Double.isFinite(ux) || !Double.isFinite(uy) || !Double.isFinite(uz))
                        throw new SimulationException(context.step(), x, y, z, "non-finite velocity");
                    int destination = x + nx * (y + ny * z);
                    for (int i = 0; i < D3Q19.Q; i++) {
                        double value =
                                incoming[i] - context.omega() * (incoming[i] - D3Q19.equilibrium(i, rho, ux, uy, uz));
                        if (!Double.isFinite(value))
                            throw new SimulationException(
                                    context.step(), x, y, z, "non-finite collision population direction=" + i);
                        next[i][destination] = value;
                    }
                }
            }
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
