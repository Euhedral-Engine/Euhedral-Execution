package io.euhedral_execution.benchmarks.cfd.config;

/// Dimensions shared by domains and bricks; no cell-sized storage is created here.
public record GridShape(int nx, int ny, int nz) {
    public GridShape {
        Checks.require(nx > 0 && ny > 0 && nz > 0, "grid dimensions must be positive: " + nx + "x" + ny + "x" + nz);
    }

    public long cellCount() {
        try {
            return Math.multiplyExact(Math.multiplyExact((long) nx, ny), nz);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("cell count overflows for grid " + this, e);
        }
    }

    public long brickCount(GridShape brick) {
        return Math.multiplyExact(
                Math.multiplyExact(ceilDiv(nx, brick.nx), ceilDiv(ny, brick.ny)), ceilDiv(nz, brick.nz));
    }

    public long frameCount(GridShape brick, int bricksPerFrame) {
        Checks.require(bricksPerFrame > 0, "bricksPerFrame must be positive");
        return (brickCount(brick) - 1) / bricksPerFrame + 1;
    }

    private static long ceilDiv(int length, int size) {
        return ((long) length + size - 1) / size;
    }

    @Override
    public String toString() {
        return nx + "x" + ny + "x" + nz;
    }
}
