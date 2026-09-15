package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import java.util.ArrayList;

/// Stable X-fastest brick ordinals grouped into contiguous scheduler batches.
public final class RangePlan {
    private final GeometryMask geometry;
    private final GridShape brick;
    private final int columns, rows, count, bricksPerFrame, frameCount;

    public RangePlan(GeometryMask geometry, GridShape brick) {
        this(geometry, brick, 1);
    }

    public RangePlan(GeometryMask geometry, GridShape brick, int bricksPerFrame) {
        if (bricksPerFrame <= 0) {
            throw new IllegalArgumentException("bricksPerFrame must be positive");
        }
        this.bricksPerFrame = bricksPerFrame;
        this.geometry = java.util.Objects.requireNonNull(geometry);
        this.brick = java.util.Objects.requireNonNull(brick);
        columns = (geometry.shape().nx() - 1) / brick.nx() + 1;
        rows = (geometry.shape().ny() - 1) / brick.ny() + 1;
        count = Math.toIntExact(geometry.shape().brickCount(brick));
        frameCount = Math.toIntExact(geometry.shape().frameCount(brick, bricksPerFrame));
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

    public int frameCount() {
        return frameCount;
    }

    public int firstBrick(int batch) {
        if (batch < 0 || batch >= frameCount) {
            throw new IllegalArgumentException("batch ordinal outside plan");
        }
        return Math.multiplyExact(batch, bricksPerFrame);
    }

    public int bricksInFrame(int batch) {
        return Math.min(bricksPerFrame, count - firstBrick(batch));
    }

    public boolean batchHasFluid(int batch) {
        int first = firstBrick(batch), end = first + bricksInFrame(batch);
        for (int ordinal = first; ordinal < end; ordinal++) {
            if (hasFluid(ordinal)) {
                return true;
            }
        }
        return false;
    }

    public void replace(
            CfdRangeFrame frame, io.euhedral_execution.benchmarks.cfd.solver.StepContext context, int batch) {
        frame.replace(context, this, batch);
    }

    public CfdRangeFrame[] createFrames() {
        var frames = new ArrayList<CfdRangeFrame>();
        for (int batch = 0; batch < frameCount; batch++) {
            if (batchHasFluid(batch)) {
                var frame = new CfdRangeFrame(1, this, batch);
                frame.reserveForceStorage(geometry.forceCount());
                frames.add(frame);
            }
        }
        return frames.toArray(CfdRangeFrame[]::new);
    }

    public static CfdRangeFrame[] create(GeometryMask geometry, GridShape brick) {
        return new RangePlan(geometry, brick).createFrames();
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
