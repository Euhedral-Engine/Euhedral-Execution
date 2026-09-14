package io.euhedral_execution.benchmarks.cfd.geometry;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.*;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.*;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
class StlGeometryTest {
    private static ControlPlaneLattice lattice;

    @BeforeAll
    static void start() {
        lattice = CfdTestRuntime.singleWorker();
    }

    @AfterAll
    static void stop() {
        lattice.close();
    }

    private GeometryMask resolve(CfdConfiguration config) {
        return GeometryMask.resolve(config, lattice);
    }

    @TempDir
    Path directory;

    private static final int[][] FACES = {
        {0, 2, 1}, {0, 3, 2}, {4, 5, 6}, {4, 6, 7}, {0, 4, 7}, {0, 7, 3}, {1, 2, 6}, {1, 6, 5}, {0, 1, 5}, {0, 5, 4},
        {3, 7, 6}, {3, 6, 2}
    };

    static double[] box(double min, double max) {
        return box(min, min, min, max, max, max);
    }

    static double[] box(double x0, double y0, double z0, double x1, double y1, double z1) {
        double[][] v = {
            {x0, y0, z0},
            {x1, y0, z0},
            {x1, y1, z0},
            {x0, y1, z0},
            {x0, y0, z1},
            {x1, y0, z1},
            {x1, y1, z1},
            {x0, y1, z1}
        };
        double[] triangles = new double[108];
        int i = 0;
        for (int[] face : FACES) for (int vertex : face) for (double value : v[vertex]) triangles[i++] = value;
        return triangles;
    }

    private static double[] combine(double[] a, double[] b, boolean reverseB) {
        double[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        if (reverseB)
            for (int i = a.length; i < result.length; i += 9)
                for (int k = 0; k < 3; k++) {
                    double value = result[i + 3 + k];
                    result[i + 3 + k] = result[i + 6 + k];
                    result[i + 6 + k] = value;
                }
        return result;
    }

    private Path write(String name, double[] triangles, boolean binary) throws IOException {
        Path file = directory.resolve(name);
        if (binary) {
            byte[] bytes = new byte[84 + triangles.length / 9 * 50];
            System.arraycopy(
                    "solid misleading binary header".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    0,
                    bytes,
                    0,
                    30);
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(80, triangles.length / 9);
            for (int t = 0; t < triangles.length / 9; t++)
                for (int c = 0; c < 9; c++) buffer.putFloat(84 + 50 * t + 12 + c * 4, (float) triangles[t * 9 + c]);
            Files.write(file, bytes);
        } else {
            var text = new StringBuilder("solid fixture\n");
            for (int t = 0; t < triangles.length; t += 9) {
                text.append("facet normal 0 0 0\nouter loop\n");
                for (int v = 0; v < 3; v++)
                    text.append("vertex ")
                            .append(triangles[t + 3 * v])
                            .append(' ')
                            .append(triangles[t + 3 * v + 1])
                            .append(' ')
                            .append(triangles[t + 3 * v + 2])
                            .append('\n');
                text.append("endloop\nendfacet\n");
            }
            Files.writeString(file, text.append("endsolid fixture\n"));
        }
        return file;
    }

    private Mesh mesh(Path file) {
        return new Mesh(7, "fixture", file.getFileName().toString(), MeshUnits.LATTICE, null, null, null, null);
    }

    private CfdConfiguration config(Mesh mesh) {
        return config(new Geometry(null, null, null, null, null, List.of(mesh)), new GridShape(10, 11, 12), null);
    }

    private CfdConfiguration config(Geometry geometry, GridShape grid, Physics physics) {
        return ConfigLoader.resolve(
                directory.resolve("config.json"),
                new SimulationConfig(1, grid, physics, geometry, null, null, 64_000_000L),
                64_000_000);
    }

    private TriangleMesh load(Path path) {
        var config = config(mesh(path));
        return new TriangleMesh(config.meshes().getFirst(), config.physics());
    }

    @Test
    void asciiAndBinaryAgreeIncludingSolidHeaderAndConservativeBoxBand() throws Exception {
        Path ascii = write("ascii.stl", box(3, 6), false), binary = write("binary.stl", box(3, 6), true);
        var a = resolve(config(mesh(ascii)));
        var b = resolve(config(mesh(binary)));
        assertEquals(12, a.voxelization().meshes().getFirst().source().triangles());
        assertEquals("binary", b.voxelization().meshes().getFirst().source().encoding());
        for (int z = 0; z < 12; z++)
            for (int y = 0; y < 11; y++)
                for (int x = 0; x < 10; x++) {
                    int i = x + 10 * (y + 11 * z);
                    boolean solid = x >= 2 && x <= 6 && y >= 2 && y <= 6 && z >= 2 && z <= 6;
                    assertEquals(solid ? 7 : 0, a.obstacleId(i), "cell " + x + "," + y + "," + z);
                    assertEquals(a.obstacleId(i), b.obstacleId(i));
                }
        assertEquals(8, a.voxelization().meshes().getFirst().weldedVertices());
        assertEquals(1, a.voxelization().connectivity().d3q19Components());
        assertEquals(0, a.voxelization().connectivity().cornerOnlyLinks());
        assertEquals(7, a.forceId(0));
    }

    @Test
    void sharedRayEdgesVerticesAndTriangleOrderHaveDeterministicParity() throws Exception {
        double[] triangles = box(2, 7);
        var mesh = load(write("box.stl", triangles, false));
        for (double y : new double[] {2, 2.5, 4.5, 7})
            for (double z : new double[] {2, 2.5, 4.5, 7}) {
                assertFalse(mesh.inside(1, y, z), "two surface crossings must cancel");
                if (y > 2 && y < 7 && z > 2 && z < 7) assertTrue(mesh.inside(4, y, z));
                assertFalse(mesh.inside(8, y, z));
            }
        double[] reversed = new double[triangles.length];
        for (int t = 0; t < 12; t++) System.arraycopy(triangles, t * 9, reversed, (11 - t) * 9, 9);
        var other = load(write("reordered.stl", reversed, false));
        for (int y = 0; y <= 8; y++)
            for (int z = 0; z <= 8; z++) assertEquals(mesh.inside(4, y, z), other.inside(4, y, z));
    }

    @Test
    void nestedShellsMakeCavitiesAndRequireAlternatingWinding() throws Exception {
        Path path = write("cavity.stl", combine(box(1, 9), box(3, 7), true), false);
        var config = config(mesh(path));
        var mesh = new TriangleMesh(config.meshes().getFirst(), config.physics());
        assertEquals(2, mesh.shells);
        assertEquals(1, mesh.cavities);
        assertTrue(mesh.inside(2, 5, 5));
        assertFalse(mesh.inside(5, 5, 5));
        var mask = resolve(config);
        assertEquals(0, mask.obstacleId(5 + 10 * (5 + 11 * 5)));
        assertEquals(2, mask.voxelization().connectivity().d3q19Components());
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> load(write("wrong-winding.stl", combine(box(1, 9), box(3, 7), false), false)))
                .getMessage()
                .contains("nested-shell winding"));
    }

    @Test
    void malformedAndUnboundedInputIsRejectedBeforeMeshAllocation() throws Exception {
        byte[] valid = Files.readAllBytes(write("valid.stl", box(2, 4), true));
        for (int length : new int[] {0, 79, 83, valid.length - 1, valid.length + 1}) {
            Path path = directory.resolve("truncated.stl");
            Files.write(path, Arrays.copyOf(valid, length));
            assertThrows(IllegalArgumentException.class, () -> config(mesh(path)));
        }
        Path huge = directory.resolve("huge.stl");
        try (var file = new RandomAccessFile(huge.toFile(), "rw")) {
            file.setLength(84 + 50L * 1_000_000);
            file.seek(80);
            file.writeInt(Integer.reverseBytes(1_000_000));
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> config(mesh(huge)))
                .getMessage()
                .contains("preprocessing limit"));
        Path ascii = write("bad-ascii.stl", box(2, 4), false);
        for (String bad : new String[] {"NaN", "Infinity", "x", "1".repeat(129)}) {
            String text = Files.readString(ascii).replaceFirst("vertex 2.0", "vertex " + bad);
            Path path = directory.resolve("bad.stl");
            Files.writeString(path, text);
            assertThrows(IllegalArgumentException.class, () -> config(mesh(path)));
        }
        valid[96] = 0;
        valid[97] = 0;
        valid[98] = (byte) 0xc0;
        valid[99] = 0x7f;
        Path path = directory.resolve("nan.stl");
        Files.write(path, valid);
        assertThrows(IllegalArgumentException.class, () -> config(mesh(path)));
    }

    @Test
    void rejectsOpenDegenerateNonmanifoldAndIntersectingMeshes() throws Exception {
        double[] cube = box(2, 5);
        assertTrue(assertThrows(
                        IllegalArgumentException.class, () -> load(write("open.stl", Arrays.copyOf(cube, 99), false)))
                .getMessage()
                .contains("open edge"));
        double[] degenerate = cube.clone();
        System.arraycopy(degenerate, 0, degenerate, 3, 3);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> load(write("degenerate.stl", degenerate, false)))
                .getMessage()
                .contains("degenerate"));
        assertThrows(
                IllegalArgumentException.class,
                () -> load(write("nonmanifold.stl", combine(cube, Arrays.copyOf(cube, 9), false), false)));
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> load(write("intersect.stl", combine(cube, box(3, 6), false), false)))
                .getMessage()
                .contains("self-intersection"));
        double[] winding = cube.clone();
        for (int a = 0; a < 3; a++) {
            double v = winding[3 + a];
            winding[3 + a] = winding[6 + a];
            winding[6 + a] = v;
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> load(write("winding.stl", winding, false)))
                .getMessage()
                .contains("inconsistent winding"));
    }

    @Test
    void transformsUnitsBoundsAndBudgetsAreChecked() throws Exception {
        Path path = write("unit.stl", box(0, 1), false);
        var spec = new Mesh(
                7,
                "transformed",
                path.toString(),
                MeshUnits.MILLIMETERS,
                new Vector3(2, 3, 4),
                new Vector3(0, 0, 90),
                new Vector3(.01, .02, .03),
                null);
        var physics = new Physics(null, null, new Physical(.001, .0001, 1000, .001, null, null), null);
        var config =
                config(new Geometry(null, null, null, null, null, List.of(spec)), new GridShape(10, 11, 12), physics);
        var info = config.meshes().getFirst();
        assertEquals(.007, info.min().x(), 1e-15);
        assertEquals(.01, info.max().x(), 1e-15);
        assertEquals(.022, info.max().y(), 1e-15);
        assertEquals(.034, info.max().z(), 1e-15);
        var xyz = new Mesh(
                7,
                "xyz",
                path.toString(),
                MeshUnits.MILLIMETERS,
                new Vector3(2, 3, 4),
                new Vector3(90, 90, 0),
                new Vector3(.01, .02, .03),
                null);
        var rotated =
                config(new Geometry(null, null, null, null, null, List.of(xyz)), new GridShape(10, 11, 12), physics);
        var xyzInfo = rotated.meshes().getFirst();
        assertEquals(.013, xyzInfo.max().x(), 1e-15);
        assertEquals(.016, xyzInfo.min().y(), 1e-15);
        assertEquals(.028, xyzInfo.min().z(), 1e-15);
        assertTrue(new TriangleMesh(xyzInfo, rotated.physics()).inside(.0115, .018, .029));
        assertThrows(IllegalArgumentException.class, () -> config(spec));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Mesh(7, null, path.toString(), MeshUnits.LATTICE, new Vector3(1, 0, 1), null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> config(new Mesh(7, null, path.toString(), MeshUnits.LATTICE, null, null, null, 1.0)));
        long required = config.memory().totalBytes();
        var input = config.config();
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigLoader.resolve(
                        directory.resolve("budget.json"),
                        new SimulationConfig(1, input.grid(), physics, input.geometry(), null, null, required - 1),
                        required - 1));
    }

    @Test
    void meshFingerprintPreventsChangedInputAndOverlapsUseLowestId() throws Exception {
        Path path = write("box.stl", box(3, 5), false);
        var config = config(mesh(path));
        Files.writeString(path, Files.readString(path).replace("3.0", "2.0"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> resolve(config))
                .getMessage()
                .contains("changed after inspection"));
        var geometry = new Geometry(
                null,
                List.of(new Box(2, new Vector3(2, 2, 2), new Vector3(6, 6, 6))),
                null,
                null,
                null,
                List.of(mesh(path)));
        var mask = resolve(config(geometry, new GridShape(10, 11, 12), null));
        assertEquals(2, mask.obstacleId(4 + 10 * (4 + 11 * 4)));
        assertEquals(2, mask.forceCount());
        assertThrows(
                IllegalArgumentException.class,
                () -> new Geometry(
                        null,
                        List.of(new Box(7, Vector3.ZERO, new Vector3(1, 1, 1))),
                        null,
                        null,
                        null,
                        List.of(mesh(path))));
    }

    private CfdConfiguration passage(Path file, double dx) {
        var mesh = new Mesh(7, "passage", file.toString(), MeshUnits.METERS, null, null, null, null);
        var faces = new Faces(
                FaceCondition.VELOCITY_INLET,
                FaceCondition.DENSITY_OUTLET,
                FaceCondition.WALL,
                FaceCondition.WALL,
                FaceCondition.WALL,
                FaceCondition.WALL);
        var geometry = new Geometry(
                faces, null, null, null, new OpenBoundary(new Vector3(.001, 0, 0), null, null), List.of(mesh));
        var physics = new Physics(null, null, new Physical(dx, .1 * dx * dx, 1000, 1, null, null), null);
        return config(geometry, new GridShape((int) (10 / dx), (int) (12 / dx), (int) (12 / dx)), physics);
    }

    @Test
    void passagesAtTwoResolutionsExposeNarrowGapsAndBlockUnresolvedInlets() throws Exception {
        Path slit = write("slit.stl", combine(box(3, -2, -2, 5, 4.4, 14), box(3, 6.6, -2, 5, 14, 14), false), false);
        var coarse = resolve(passage(slit, 1)).voxelization().connectivity();
        var fine = resolve(passage(slit, .5)).voxelization().connectivity();
        assertEquals(1, coarse.throughComponents());
        assertEquals(1, fine.throughComponents());
        assertTrue(coarse.oneCellGapCells() > 0);
        assertEquals(0, fine.oneCellGapCells());
        assertEquals(0, coarse.cornerOnlyLinks());
        assertEquals(0, fine.cornerOnlyLinks());
        assertTrue(fine.axisLinks() > 0 && fine.diagonalLinks() > 0);
        Path narrow =
                write("closed-slit.stl", combine(box(3, -2, -2, 5, 4.6, 14), box(3, 5.4, -2, 5, 14, 14), false), false);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> resolve(passage(narrow, 1)))
                .getMessage()
                .contains("blocks every D3Q19"));
        assertEquals(
                1, resolve(passage(narrow, .25)).voxelization().connectivity().throughComponents());
    }

    @Test
    void connectivityCountsDiagonalOnlyRoutesAndDisconnectedFluid() {
        var shape = new GridShape(3, 3, 3);
        int[] ids = new int[27];
        Arrays.fill(ids, 7);
        ids[0] = 0;
        ids[4] = 0;
        var walls = new Faces(
                FaceCondition.WALL,
                FaceCondition.WALL,
                FaceCondition.WALL,
                FaceCondition.WALL,
                FaceCondition.WALL,
                FaceCondition.WALL);
        var report = Voxelization.connectivity(shape, ids, walls);
        assertEquals(2, report.axisComponents());
        assertEquals(1, report.d3q19Components());
        assertEquals(0, report.axisLinks());
        assertEquals(1, report.diagonalLinks());
        assertEquals(1, report.cornerOnlyLinks());
        ids[26] = 0;
        assertEquals(2, Voxelization.connectivity(shape, ids, walls).d3q19Components());
    }

    @Test
    void conservativeSurfaceStopsAxisAndDiagonalLinksAcrossAnObliqueThinWall() throws Exception {
        Path path = write("thin.stl", box(-10, -.01, -10, 10, .01, 10), false);
        var spec = new Mesh(
                7,
                "oblique",
                path.toString(),
                MeshUnits.LATTICE,
                null,
                new Vector3(0, 0, 45),
                new Vector3(5, 5, 5),
                null);
        var walls = new Faces(
                FaceCondition.WALL,
                FaceCondition.WALL,
                FaceCondition.WALL,
                FaceCondition.WALL,
                FaceCondition.WALL,
                FaceCondition.WALL);
        var mask = resolve(
                config(new Geometry(walls, null, null, null, null, List.of(spec)), new GridShape(10, 11, 12), null));
        assertEquals(2, mask.voxelization().connectivity().axisComponents());
        assertEquals(2, mask.voxelization().connectivity().d3q19Components());
        for (int z = 0; z < 12; z++)
            for (int y = 0; y < 11; y++)
                for (int x = 0; x < 10; x++) {
                    if (mask.isSolid(x + 10 * (y + 11 * z))) continue;
                    for (int q = 1; q < 19; q++) {
                        int nx = x + io.euhedral_execution.benchmarks.cfd.solver.D3Q19.x(q),
                                ny = y + io.euhedral_execution.benchmarks.cfd.solver.D3Q19.y(q),
                                nz = z + io.euhedral_execution.benchmarks.cfd.solver.D3Q19.z(q);
                        if (nx < 0
                                || nx >= 10
                                || ny < 0
                                || ny >= 11
                                || nz < 0
                                || nz >= 12
                                || mask.isSolid(nx + 10 * (ny + 11 * nz))) continue;
                        assertTrue((long) (y - x) * (ny - nx) > 0, "fluid link crosses mesh wall");
                    }
                }
    }

    @Test
    void toleranceWeldsSmallCracksButRejectsPinchedShellContactsAndInterruption() throws Exception {
        double[] cube = box(2, 5);
        cube[0] += 1e-9;
        var mesh = load(write("weld.stl", cube, false));
        assertEquals(8, mesh.vertexCount);
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> load(write("pinch.stl", combine(box(1, 3), box(3, 5), false), false)))
                .getMessage()
                .contains("nonmanifold vertex"));
        Path path = write("interrupt.stl", box(2, 5), false);
        var config = config(mesh(path));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalArgumentException.class, () -> resolve(config));
        } finally {
            Thread.interrupted();
        }
    }
}
