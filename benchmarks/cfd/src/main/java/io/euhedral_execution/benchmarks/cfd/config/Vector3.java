package io.euhedral_execution.benchmarks.cfd.config;

public record Vector3(double x, double y, double z) {
    public static final Vector3 ZERO = new Vector3(0, 0, 0);

    public Vector3 {
        Checks.finite(x, "vector.x");
        Checks.finite(y, "vector.y");
        Checks.finite(z, "vector.z");
        Checks.finite(Math.hypot(Math.hypot(x, y), z), "vector magnitude");
    }

    public double magnitude() {
        return Math.hypot(Math.hypot(x, y), z);
    }

    public Vector3 scale(double factor) {
        Checks.positive(factor, "unit conversion factor");
        return new Vector3(x * factor, y * factor, z * factor);
    }
}
