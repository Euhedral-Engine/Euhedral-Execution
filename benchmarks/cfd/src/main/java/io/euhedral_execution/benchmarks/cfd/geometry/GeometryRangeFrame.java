package io.euhedral_execution.benchmarks.cfd.geometry;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.frames.CfdFrame;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.core.impl.FrameManager;

/// Reusable preprocessing work. The owner consumes terminal results before replacing scalar bounds.
/// Mesh index arrays are read-only; each painting range exclusively owns its destination cells.
final class GeometryRangeFrame extends CfdFrame {
    private TriangleMesh mesh;
    private GridShape shape;
    private int[] ids;
    private int x0, x1, y0, y1, z0, z1, fromTriangle, toTriangle;
    private boolean validation;
    private double dx;
    private long started, timeout;
    private long surface, interior;
    private final double[] pairA = new double[9], pairB = new double[9];
    private final Runnable checkpoint = this::checkProgress;

    GeometryRangeFrame(long id, FrameManager<?, ?> manager) {
        super(id, manager);
    }

    void validate(TriangleMesh mesh, int from, int to, long started, long timeout) {
        if (from < 0 || to <= from || to > mesh.info.triangles())
            throw new IllegalArgumentException("invalid triangle range");
        prepare(mesh, started, timeout);
        validation = true;
        fromTriangle = from;
        toTriangle = to;
        ready();
    }

    void paint(
            TriangleMesh mesh,
            GridShape shape,
            int[] ids,
            double dx,
            int x0,
            int x1,
            int y0,
            int y1,
            int z0,
            int z1,
            long started,
            long timeout) {
        if (ids.length != shape.cellCount()
                || !Double.isFinite(dx)
                || dx <= 0
                || x0 < 0
                || y0 < 0
                || z0 < 0
                || x1 <= x0
                || y1 <= y0
                || z1 <= z0
                || x1 > shape.nx()
                || y1 > shape.ny()
                || z1 > shape.nz()) throw new IllegalArgumentException("invalid voxel range");
        prepare(mesh, started, timeout);
        validation = false;
        this.shape = shape;
        this.ids = ids;
        this.dx = dx;
        this.x0 = x0;
        this.x1 = x1;
        this.y0 = y0;
        this.y1 = y1;
        this.z0 = z0;
        this.z1 = z1;
        ready();
    }

    private void prepare(TriangleMesh mesh, long started, long timeout) {
        if (mesh == null || timeout <= 0) throw new IllegalArgumentException("mesh and positive timeout required");
        beginPreparation();
        this.mesh = mesh;
        this.started = started;
        this.timeout = timeout;
        ids = null;
        shape = null;
        surface = 0;
        interior = 0;
    }

    long surfaceCells() {
        requireSuccess();
        return surface;
    }

    long interiorCells() {
        requireSuccess();
        return interior;
    }

    private void checkProgress() {
        if (!isAlive()) throwCancelSignal();
        if (Thread.currentThread().isInterrupted())
            throw new SimulationException(0, 0, 0, 0, "interrupted during mesh frame execution");
        if (System.nanoTime() - started >= timeout)
            throw new SimulationException(0, 0, 0, 0, "mesh preprocessing stage deadline exceeded");
    }

    @Override
    protected void executeBody() {
        if (validation) {
            for (int t = fromTriangle; t < toTriangle; t++) mesh.validateTriangle(t, pairA, pairB, checkpoint);
            return;
        }
        int id = mesh.info.mesh().id();
        for (int z = z0; z < z1; z++)
            for (int y = y0; y < y1; y++)
                for (int x = x0; x < x1; x++) {
                    if ((x - x0) % 64 == 0) checkProgress();
                    double px = (x + .5) * dx, py = (y + .5) * dx, pz = (z + .5) * dx;
                    boolean boundary = mesh.surface(px, py, pz, dx / 2);
                    if (!boundary && !mesh.inside(px, py, pz)) continue;
                    if (boundary) surface++;
                    else interior++;
                    int index = x + shape.nx() * (y + shape.ny() * z);
                    if (ids[index] == 0 || id < ids[index]) ids[index] = id;
                }
        checkProgress();
    }

    @Override
    protected void publishSuccess() {
        checkProgress();
    }

    @Override
    protected long generation() {
        return 0;
    }
}
