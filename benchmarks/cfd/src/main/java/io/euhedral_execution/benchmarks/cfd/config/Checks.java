package io.euhedral_execution.benchmarks.cfd.config;

final class Checks {
    private Checks() {}

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static double finite(double value, String name) {
        require(Double.isFinite(value), name + " must be finite");
        return value;
    }

    static double positive(double value, String name) {
        finite(value, name);
        require(value > 0, name + " must be positive");
        return value;
    }
}
