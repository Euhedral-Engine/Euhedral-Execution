package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import java.util.ArrayList;

/// Stable Z/Y/X brick ordinals, with solid-only bricks omitted by every backend.
public final class RangePlan {
    private final GeometryMask geometry;
    private final GridShape brick;
    private final int columns, rows, count;

    public RangePlan(GeometryMask geometry, GridShape brick) {
        this.geometry = java.util.Objects.requireNonNull(geometry);
        this.brick = java.util.Objects.requireNonNull(brick);
        columns = (geometry.shape().nx() - 1) / brick.nx() + 1;
        rows = (geometry.shape().ny() - 1) / brick.ny() + 1;
        count = Math.toIntExact(geometry.shape().brickCount(brick));
    }

    public int count() {
        return count;
    }

    public GeometryMask geometry() {
        return geometry;
    }

    public int xFrom(int ordinal) {
        return ordinal % columns * brick.nx();
    }

    public int yFrom(int ordinal) {
        return ordinal / columns % rows * brick.ny();
    }

    public int zFrom(int ordinal) {
        return ordinal / columns / rows * brick.nz();
    }

    public int xTo(int ordinal) {
        return (int)
                Math.min((long) xFrom(ordinal) + brick.nx(), geometry.shape().nx());
    }

    public int yTo(int ordinal) {
        return (int)
                Math.min((long) yFrom(ordinal) + brick.ny(), geometry.shape().ny());
    }

    public int zTo(int ordinal) {
        return (int)
                Math.min((long) zFrom(ordinal) + brick.nz(), geometry.shape().nz());
    }

    public boolean hasFluid(int ordinal) {
        return hasFluid(
                geometry, xFrom(ordinal), xTo(ordinal), yFrom(ordinal), yTo(ordinal), zFrom(ordinal), zTo(ordinal));
    }

    public void replace(
            CfdRangeFrame frame, io.euhedral_execution.benchmarks.cfd.solver.StepContext context, int ordinal) {
        frame.replace(
                context,
                ordinal,
                xFrom(ordinal),
                xTo(ordinal),
                yFrom(ordinal),
                yTo(ordinal),
                zFrom(ordinal),
                zTo(ordinal));
    }

    public CfdRangeFrame[] createFrames() {
        return create(geometry, brick);
    }

    public static CfdRangeFrame[] create(GeometryMask geometry, GridShape brick) {
        var shape = geometry.shape();
        var frames = new ArrayList<CfdRangeFrame>();
        int ordinal = 0;
        for (int z = 0, zTo; z < shape.nz(); z = zTo) {
            zTo = (int) Math.min((long) z + brick.nz(), shape.nz());
            for (int y = 0, yTo; y < shape.ny(); y = yTo) {
                yTo = (int) Math.min((long) y + brick.ny(), shape.ny());
                for (int x = 0, xTo; x < shape.nx(); x = xTo) {
                    xTo = (int) Math.min((long) x + brick.nx(), shape.nx());
                    if (hasFluid(geometry, x, xTo, y, yTo, z, zTo)) {
                        var frame = new CfdRangeFrame(1, ordinal, x, xTo, y, yTo, z, zTo);
                        frame.reserveForceStorage(geometry.forceCount());
                        frames.add(frame);
                    }
                    ordinal = Math.incrementExact(ordinal);
                }
            }
        }
        return frames.toArray(CfdRangeFrame[]::new);
    }

    private static boolean hasFluid(GeometryMask geometry, int xFrom, int xTo, int yFrom, int yTo, int zFrom, int zTo) {
        var shape = geometry.shape();
        for (int z = zFrom; z < zTo; z++) {
            for (int y = yFrom; y < yTo; y++) {
                for (int x = xFrom; x < xTo; x++) {
                    if (!geometry.isSolid(x + shape.nx() * (y + shape.ny() * z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
