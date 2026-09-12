package io.euhedral_execution.core.utils;

public class MathFunctions {
    private MathFunctions() {}

    public static long unsignedMultiplyHigh(long a, long b) {
        long signedHigh = Math.multiplyHigh(a, b);
        return signedHigh + ((a >> 63) & b) + ((b >> 63) & a);
    }

    public static long clampLong(long val, long min, long max) {
        return Math.min(max, Math.max(min, val));
    }

    public static double clampDouble(double val, double min, double max) {
        return Math.min(max, Math.max(min, val));
    }

    public static double ewma(double curr, double next, double alpha) {
        return (curr * (1.0 - alpha)) + (next * alpha);
    }

    public static long ewma(long curr, long next, double alpha) {
        return (long) ewma((double) curr, (double) next, alpha);
    }
}
