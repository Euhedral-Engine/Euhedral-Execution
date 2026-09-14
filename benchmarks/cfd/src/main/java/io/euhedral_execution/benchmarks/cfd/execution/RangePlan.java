package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import java.util.ArrayList;

/// Stable Z/Y/X brick ordinals, with solid-only bricks omitted by every backend.
public final class RangePlan {
    private RangePlan() {}

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
