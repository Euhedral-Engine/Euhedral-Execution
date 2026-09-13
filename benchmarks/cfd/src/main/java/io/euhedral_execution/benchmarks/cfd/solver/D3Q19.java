package io.euhedral_execution.benchmarks.cfd.solver;

/// Immutable D3Q19 direction order and weights from `NUMERICS.md`.
public final class D3Q19 {
    public static final int Q = 19;
    public static final double CS2 = 1.0 / 3.0;
    private static final int[] X = {0, 1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0};
    private static final int[] Y = {0, 0, 0, 1, -1, 0, 0, 1, -1, -1, 1, 0, 0, 0, 0, 1, -1, 1, -1};
    private static final int[] Z = {0, 0, 0, 0, 0, 1, -1, 0, 0, 0, 0, 1, -1, -1, 1, 1, -1, -1, 1};
    private static final int[] OPPOSITE = {0, 2, 1, 4, 3, 6, 5, 8, 7, 10, 9, 12, 11, 14, 13, 16, 15, 18, 17};

    private D3Q19() {}

    public static int x(int direction) {
        return X[direction];
    }

    public static int y(int direction) {
        return Y[direction];
    }

    public static int z(int direction) {
        return Z[direction];
    }

    public static int opposite(int direction) {
        return OPPOSITE[direction];
    }

    public static double weight(int direction) {
        if (direction < 0 || direction >= Q) throw new IndexOutOfBoundsException(direction);
        return direction == 0 ? 1.0 / 3.0 : direction <= 6 ? 1.0 / 18.0 : 1.0 / 36.0;
    }

    public static double equilibrium(int direction, double density, double ux, double uy, double uz) {
        double dot = x(direction) * ux + y(direction) * uy + z(direction) * uz;
        double speedSquared = ux * ux + uy * uy + uz * uz;
        return weight(direction) * density * (1 + 3 * dot + 4.5 * dot * dot - 1.5 * speedSquared);
    }
}
