package io.euhedral_execution.benchmarks.cfd.solver;

/// Failed numerical or execution generation; the driver keeps the last completed buffer.
public final class SimulationException extends RuntimeException {
    private final long step;
    private final int x;
    private final int y;
    private final int z;

    public SimulationException(long step, int x, int y, int z, String reason) {
        super("step=" + step + " cell=(" + x + "," + y + "," + z + "): " + reason);
        this.step = step;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public long step() {
        return step;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }
}
