package io.euhedral_execution.benchmarks.cfd.geometry;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.*;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.*;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeometryMaskTest {
    private static final GridShape SHAPE = new GridShape(4, 5, 6);

    private static GeometryMask mask(Geometry geometry) {
        return GeometryMask.resolve(ConfigLoader.resolve(
                Path.of("geometry.json"),
                new SimulationConfig(1, SHAPE, null, geometry, null, null, null),
                100_000_000));
    }

    private static int id(GeometryMask mask, int x, int y, int z) {
        return mask.obstacleId(x + mask.shape().nx() * (y + mask.shape().ny() * z));
    }

    @Test
    void boxesSpheresAndFiniteCylindersClassifyCellCentersAndKeepStableIds() {
        var boxes =
                mask(new Geometry(null, List.of(new Box(4, new Vector3(1, 1, 1), new Vector3(2, 3, 4))), null, null));
        assertEquals(114, boxes.fluidCells());
        assertEquals(4, id(boxes, 1, 2, 3));
        assertEquals(0, id(boxes, 2, 2, 3));
        var spheres = mask(new Geometry(null, null, List.of(new Sphere(7, new Vector3(1, 1.5, 1.5), 0.5)), null));
        assertEquals(118, spheres.fluidCells());
        assertEquals(7, id(spheres, 0, 1, 1));
        assertEquals(7, id(spheres, 1, 1, 1));
        var cylinders = mask(new Geometry(
                null,
                null,
                null,
                List.of(new Cylinder(9, new Vector3(1.5, 1.5, 0.5), new Vector3(1.5, 1.5, 2.5), 0.4))));
        assertEquals(117, cylinders.fluidCells());
        assertEquals(9, id(cylinders, 1, 1, 0));
        assertEquals(9, id(cylinders, 1, 1, 2));
        assertEquals(0, id(cylinders, 1, 1, 3));
        var diagonal = mask(new Geometry(
                null,
                null,
                null,
                List.of(new Cylinder(11, new Vector3(0.5, 0.5, 0.5), new Vector3(2.5, 2.5, 2.5), 0.2))));
        assertEquals(117, diagonal.fluidCells());
        assertEquals(11, id(diagonal, 1, 1, 1));
        assertEquals(0, id(diagonal, 1, 1, 2));
    }

    @Test
    void overlapsPreferLowestIdIndependentlyOfDeclarationOrder() {
        var first = new Box(8, Vector3.ZERO, new Vector3(2, 2, 2));
        var second = new Box(3, new Vector3(1, 1, 1), new Vector3(3, 3, 3));
        var a = mask(new Geometry(null, List.of(first, second), null, null));
        var b = mask(new Geometry(null, List.of(second, first), null, null));
        for (int i = 0; i < 120; i++) assertEquals(a.obstacleId(i), b.obstacleId(i));
        assertEquals(3, id(a, 1, 1, 1));
    }

    @Test
    void exteriorWallsDoNotConsumeFluidCellsAndPeriodicAxesWrapPastOtherFaces() {
        var mask = mask(new Geometry(
                new Faces(null, null, FaceCondition.WALL, FaceCondition.WALL, FaceCondition.WALL, FaceCondition.WALL)));
        assertEquals(120, mask.fluidCells());
        assertEquals(0, mask.wallId(-1, 0, 0));
        assertEquals(-3, mask.wallId(-1, -1, 0));
        assertEquals(-4, mask.wallId(4, 5, 6));
        assertEquals(-5, mask.wallId(0, 0, -1));
        assertEquals(-6, mask.wallId(0, 0, 6));
        var closed = mask(new Geometry(new Faces(FaceCondition.WALL, FaceCondition.WALL, null, null, null, null)));
        assertEquals(-1, closed.wallId(-1, 0, 0));
        assertEquals(-2, closed.wallId(4, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> mask.obstacleId(120));
    }

    @Test
    void physicalGeometryUsesMetersAtTheSameResolvedCellCenters() {
        var physical = new Physics(1.0, null, new Physical(0.1, 0.01, 1000, 0.01, null, null), null);
        var geometry = new Geometry(
                null, List.of(new Box(2, new Vector3(0.1, 0.1, 0.1), new Vector3(0.2, 0.3, 0.4))), null, null);
        var mask = GeometryMask.resolve(ConfigLoader.resolve(
                Path.of("physical.json"),
                new SimulationConfig(1, SHAPE, physical, geometry, null, null, null),
                100_000_000));
        assertEquals(114, mask.fluidCells());
        assertEquals(2, id(mask, 1, 2, 3));
    }

    @Test
    void invalidGeometryAndEmptyFluidDomainsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mask(new Geometry(null, List.of(new Box(1, Vector3.ZERO, new Vector3(4, 5, 6))), null, null)));
        assertThrows(IllegalArgumentException.class, () -> new Box(0, Vector3.ZERO, new Vector3(1, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new Box(1, Vector3.ZERO, Vector3.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Sphere(1, Vector3.ZERO, -1));
        assertThrows(IllegalArgumentException.class, () -> new Cylinder(1, Vector3.ZERO, Vector3.ZERO, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Cylinder(1, new Vector3(-1e308, 0, 0), new Vector3(1e308, 0, 0), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Geometry(
                        null,
                        List.of(new Box(1, Vector3.ZERO, new Vector3(1, 1, 1))),
                        List.of(new Sphere(1, Vector3.ZERO, 1)),
                        null));
        assertThrows(IllegalArgumentException.class, () -> new Faces(null, null, FaceCondition.WALL, null, null, null));
    }

    @Test
    void interruptedGeometrySetupPreservesTheFlagAndReportsInitializationContext() {
        Thread.currentThread().interrupt();
        try {
            var error = assertThrows(
                    SimulationException.class,
                    () -> mask(new Geometry(null, null, List.of(new Sphere(1, Vector3.ZERO, 1)), null)));
            assertEquals(0, error.step());
            assertEquals(0, error.x());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
