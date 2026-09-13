package io.euhedral_execution.benchmarks.cfd.geometry;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.config.MemoryEstimate;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.FaceCondition;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import java.util.Objects;

/// Immutable cell-center classification. Zero denotes fluid, positive IDs denote obstacles.
/// Domain walls lie at 0 and the axis extent, halfway beyond the outermost fluid centers.
/// Exterior wall IDs are -1/-2 (X), -3/-4 (Y), -5/-6 (Z); intersections prefer X, then Y, then Z.
public final class GeometryMask {
    private final GridShape shape;
    private final int[] obstacleIds;
    private final boolean wallX, wallY, wallZ;
    private final long fluidCells;
    private final long cells;

    private GeometryMask(GridShape shape, int[] obstacleIds, SimulationConfig.Faces faces, long fluidCells) {
        this.shape = shape;
        this.cells = shape.cellCount();
        this.obstacleIds = obstacleIds;
        wallX = faces.xMin() == FaceCondition.WALL;
        wallY = faces.yMin() == FaceCondition.WALL;
        wallZ = faces.zMin() == FaceCondition.WALL;
        this.fluidCells = fluidCells;
    }

    public static GeometryMask periodic(GridShape shape) {
        Objects.requireNonNull(shape);
        return new GeometryMask(
                shape, null, new SimulationConfig.Faces(null, null, null, null, null, null), shape.cellCount());
    }

    public static GeometryMask resolve(CfdConfiguration configuration) {
        var shape = configuration.config().grid();
        configuration.memory().requireAllocatable(shape);
        var geometry = configuration.config().geometry();
        double dx = configuration.physics().voxelWidth();
        for (int size : new int[] {shape.nx(), shape.ny(), shape.nz()}) {
            if (!Double.isFinite(size * dx)) throw new IllegalArgumentException("physical domain extent overflows");
        }
        if (geometry.boxes().isEmpty()
                && geometry.spheres().isEmpty()
                && geometry.cylinders().isEmpty())
            return new GeometryMask(shape, null, geometry.faces(), shape.cellCount());
        if (shape.cellCount() > MemoryEstimate.MAX_ARRAY_LENGTH)
            throw new IllegalArgumentException("geometry array exceeds Java array limit");
        int[] ids = new int[(int) shape.cellCount()];
        long fluid = 0;
        for (int z = 0; z < shape.nz(); z++) {
            for (int y = 0; y < shape.ny(); y++) {
                for (int x = 0; x < shape.nx(); x++) {
                    if (x % 256 == 0 && Thread.currentThread().isInterrupted())
                        throw new SimulationException(0, x, y, z, "interrupted during geometry resolution");
                    double px = (x + 0.5) * dx, py = (y + 0.5) * dx, pz = (z + 0.5) * dx;
                    int id = 0;
                    for (var box : geometry.boxes()) {
                        if (px >= box.min().x()
                                && px <= box.max().x()
                                && py >= box.min().y()
                                && py <= box.max().y()
                                && pz >= box.min().z()
                                && pz <= box.max().z()) id = choose(id, box.id());
                    }
                    for (var sphere : geometry.spheres()) {
                        if (Math.hypot(
                                        Math.hypot(
                                                px - sphere.center().x(),
                                                py - sphere.center().y()),
                                        pz - sphere.center().z())
                                <= sphere.radius()) id = choose(id, sphere.id());
                    }
                    for (var cylinder : geometry.cylinders()) {
                        double ax = cylinder.end().x() - cylinder.start().x();
                        double ay = cylinder.end().y() - cylinder.start().y();
                        double az = cylinder.end().z() - cylinder.start().z();
                        double length = Math.hypot(Math.hypot(ax, ay), az);
                        ax /= length;
                        ay /= length;
                        az /= length;
                        double rx = px - cylinder.start().x(),
                                ry = py - cylinder.start().y(),
                                rz = pz - cylinder.start().z();
                        double along = rx * ax + ry * ay + rz * az;
                        if (along >= 0
                                && along <= length
                                && Math.hypot(Math.hypot(rx - along * ax, ry - along * ay), rz - along * az)
                                        <= cylinder.radius()) id = choose(id, cylinder.id());
                    }
                    ids[x + shape.nx() * (y + shape.ny() * z)] = id;
                    if (id == 0) fluid++;
                }
            }
        }
        if (fluid == 0) throw new IllegalArgumentException("geometry leaves no fluid cells");
        return new GeometryMask(shape, ids, geometry.faces(), fluid);
    }

    private static int choose(int current, int candidate) {
        return current == 0 ? candidate : Math.min(current, candidate);
    }

    public GridShape shape() {
        return shape;
    }

    public long fluidCells() {
        return fluidCells;
    }

    public int obstacleId(int index) {
        if (index < 0 || index >= cells) throw new IndexOutOfBoundsException(index);
        return obstacleIds == null ? 0 : obstacleIds[index];
    }

    public boolean isSolid(int index) {
        return obstacleId(index) != 0;
    }

    /// Coordinates here are unwrapped stencil neighbors, at most one cell beyond each face.
    public int wallId(int x, int y, int z) {
        if (wallX && x < 0) return -1;
        if (wallX && x >= shape.nx()) return -2;
        if (wallY && y < 0) return -3;
        if (wallY && y >= shape.ny()) return -4;
        if (wallZ && z < 0) return -5;
        if (wallZ && z >= shape.nz()) return -6;
        return 0;
    }
}
