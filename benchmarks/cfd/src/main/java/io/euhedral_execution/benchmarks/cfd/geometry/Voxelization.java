package io.euhedral_execution.benchmarks.cfd.geometry;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.FaceCondition;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.Faces;
import io.euhedral_execution.benchmarks.cfd.solver.D3Q19;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import java.util.*;

/// Preprocessing metadata is retained with the mask; triangles and connectivity scratch are released.
public final class Voxelization {
    public record MeshReport(
            StlReader.Info source,
            int weldedVertices,
            int shells,
            int cavities,
            long surfaceCells,
            long interiorCells,
            long clippedSolidCells,
            double voxelWidth) {}

    public record Connectivity(
            int axisComponents,
            int d3q19Components,
            long axisLinks,
            long diagonalLinks,
            long cornerOnlyLinks,
            long oneCellGapCells,
            long inletCells,
            long outletCells,
            int throughComponents,
            long enclosedFluidCells) {}

    public record Report(List<MeshReport> meshes, Connectivity connectivity) {
        public Report {
            meshes = List.copyOf(meshes);
        }
    }

    private Voxelization() {}

    static List<MeshReport> paint(CfdConfiguration config, int[] ids, ControlPlaneLattice lattice) {
        if (config.meshes().isEmpty()) return List.of();
        var reports = new ArrayList<MeshReport>();
        try (var work =
                new GeometryWork(config, Objects.requireNonNull(lattice, "mesh preprocessing requires a lattice"))) {
            for (var info : config.meshes()) reports.add(paintMesh(config, ids, info, work));
        }
        return reports;
    }

    private static MeshReport paintMesh(CfdConfiguration config, int[] ids, StlReader.Info info, GeometryWork work) {
        var mesh = new TriangleMesh(info, config.physics(), true);
        work.beginStage();
        for (int from = 0; from < info.triangles(); ) {
            int to = (int) Math.min(info.triangles(), (long) from + 64);
            work.next().validate(mesh, from, to, work.started(), work.timeout());
            work.submit();
            from = to;
        }
        work.finishStage();
        mesh.finishValidation();
        var shape = config.config().grid();
        var brick = config.config().execution().brick();
        double dx = config.physics().voxelWidth();
        int x0 = lower(info.min().x(), dx, shape.nx()), x1 = upper(info.max().x(), dx, shape.nx());
        int y0 = lower(info.min().y(), dx, shape.ny()), y1 = upper(info.max().y(), dx, shape.ny());
        int z0 = lower(info.min().z(), dx, shape.nz()), z1 = upper(info.max().z(), dx, shape.nz());
        work.beginStage();
        for (int z = z0; z < z1; ) {
            int toZ = (int) Math.min(z1, (long) z + brick.nz());
            for (int y = y0; y < y1; ) {
                int toY = (int) Math.min(y1, (long) y + brick.ny());
                for (int x = x0; x < x1; ) {
                    int toX = (int) Math.min(x1, (long) x + brick.nx());
                    work.next().paint(mesh, shape, ids, dx, x, toX, y, toY, z, toZ, work.started(), work.timeout());
                    work.submit();
                    x = toX;
                }
                y = toY;
            }
            z = toZ;
        }
        work.finishStage();
        return new MeshReport(
                info,
                mesh.vertexCount,
                mesh.shells,
                mesh.cavities,
                work.surfaceCells(),
                work.interiorCells(),
                work.surfaceCells() + work.interiorCells(),
                dx);
    }

    private static int lower(double value, double dx, int n) {
        return (int) Math.max(0, Math.min(n, Math.floor(value / dx) - 1));
    }

    private static int upper(double value, double dx, int n) {
        return (int) Math.max(0, Math.min(n, Math.ceil(value / dx) + 1));
    }

    static Connectivity connectivity(GridShape shape, int[] ids, Faces faces) {
        int[] labels = new int[ids.length], queue = new int[ids.length];
        int axisComponents = components(shape, ids, faces, labels, queue, 7);
        Arrays.fill(labels, 0);
        int components = components(shape, ids, faces, labels, queue, 19);
        /// Component flags fit in the already allocated BFS storage.
        Arrays.fill(queue, 0, components, 0);
        long axis = 0, diagonal = 0, corners = 0, narrow = 0, inlet = 0, outlet = 0;
        for (int i = 0; i < ids.length; i++) {
            checkInterrupted();
            if (ids[i] != 0) continue;
            int x = i % shape.nx(), y = i / shape.nx() % shape.ny(), z = i / (shape.nx() * shape.ny());
            boolean thin = false;
            for (int a = 0; a < 3; a++) {
                int minus = neighbor(shape, faces, x, y, z, a == 0 ? -1 : 0, a == 1 ? -1 : 0, a == 2 ? -1 : 0);
                int plus = neighbor(shape, faces, x, y, z, a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0);
                if (blocked(minus, ids) && blocked(plus, ids)) thin = true;
                int coordinate = a == 0 ? x : a == 1 ? y : z,
                        size = a == 0 ? shape.nx() : a == 1 ? shape.ny() : shape.nz();
                for (int side = 0; side < 2; side++)
                    if (coordinate == (side == 0 ? 0 : size - 1)) {
                        var face = faces.at(2 * a + side);
                        if (face == FaceCondition.VELOCITY_INLET) {
                            inlet++;
                            queue[labels[i] - 1] |= 1;
                        }
                        if (face == FaceCondition.DENSITY_OUTLET) {
                            outlet++;
                            queue[labels[i] - 1] |= 2;
                        }
                    }
            }
            if (thin) narrow++;
            for (int q = 1; q < 19; q++) {
                int ex = D3Q19.x(q), ey = D3Q19.y(q), ez = D3Q19.z(q);
                int j = neighbor(shape, faces, x, y, z, ex, ey, ez);
                if (j <= i || ids[j] != 0) continue;
                if (q < 7) axis++;
                else {
                    diagonal++;
                    int first, second;
                    if (ex != 0) {
                        first = neighbor(shape, faces, x, y, z, ex, 0, 0);
                        second = neighbor(shape, faces, x, y, z, 0, ey, ez);
                    } else {
                        first = neighbor(shape, faces, x, y, z, 0, ey, 0);
                        second = neighbor(shape, faces, x, y, z, 0, 0, ez);
                    }
                    if (blocked(first, ids) && blocked(second, ids)) corners++;
                }
            }
        }
        int through = 0;
        for (int c = 0; c < components; c++) if (queue[c] == 3) through++;
        long enclosed = 0;
        if (faces.openAxis() >= 0) {
            for (int i = 0; i < ids.length; i++) if (ids[i] == 0 && queue[labels[i] - 1] == 0) enclosed++;
            if (through == 0)
                throw new IllegalArgumentException(
                        "voxelized geometry blocks every D3Q19 inlet/outlet passage; refine or change the geometry");
        }
        return new Connectivity(
                axisComponents, components, axis, diagonal, corners, narrow, inlet, outlet, through, enclosed);
    }

    private static boolean blocked(int i, int[] ids) {
        return i < 0 || ids[i] != 0;
    }

    private static int components(GridShape shape, int[] ids, Faces faces, int[] labels, int[] queue, int directions) {
        int component = 0;
        for (int seed = 0; seed < ids.length; seed++) {
            if (ids[seed] != 0 || labels[seed] != 0) continue;
            labels[seed] = ++component;
            int read = 0, write = 1;
            queue[0] = seed;
            while (read < write) {
                checkInterrupted();
                int i = queue[read++],
                        x = i % shape.nx(),
                        y = i / shape.nx() % shape.ny(),
                        z = i / (shape.nx() * shape.ny());
                for (int q = 1; q < directions; q++) {
                    int j = neighbor(shape, faces, x, y, z, D3Q19.x(q), D3Q19.y(q), D3Q19.z(q));
                    if (j >= 0 && ids[j] == 0 && labels[j] == 0) {
                        labels[j] = component;
                        queue[write++] = j;
                    }
                }
            }
        }
        return component;
    }

    private static int neighbor(GridShape s, Faces faces, int x, int y, int z, int dx, int dy, int dz) {
        x += dx;
        y += dy;
        z += dz;
        if (x < 0 || x >= s.nx()) {
            if (faces.xMin() != FaceCondition.PERIODIC) return -1;
            x = Math.floorMod(x, s.nx());
        }
        if (y < 0 || y >= s.ny()) {
            if (faces.yMin() != FaceCondition.PERIODIC) return -1;
            y = Math.floorMod(y, s.ny());
        }
        if (z < 0 || z >= s.nz()) {
            if (faces.zMin() != FaceCondition.PERIODIC) return -1;
            z = Math.floorMod(z, s.nz());
        }
        return x + s.nx() * (y + s.ny() * z);
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new IllegalArgumentException("interrupted during voxelization/connectivity analysis");
    }
}
