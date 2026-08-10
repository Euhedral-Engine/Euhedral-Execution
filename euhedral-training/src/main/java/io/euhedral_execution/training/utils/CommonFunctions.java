package io.euhedral_execution.training.utils;

public class CommonFunctions {

    private static final int ACTION_WIDTH = 7;
    private static final double MIN_NORM = 1e-12;

    private CommonFunctions() {}

    public static void normalize(double[] array) {
        double squareSum = 0;
        for (double d : array) {
            squareSum += d * d;
        }

        double length = Math.max(Math.sqrt(squareSum), MIN_NORM);
        for (int i = 0; i < array.length; i++) {
            array[i] /= length;
        }
    }

    /**
     * L2 normalizes each seven-weight action block without changing the values' coordinate system.
     */
    public static void normalizePolicyVector(double[] vector) {
        if (vector.length % ACTION_WIDTH != 0) {
            throw new IllegalArgumentException("Policy vector length must be divisible by " + ACTION_WIDTH);
        }

        for (int start = 0; start < vector.length; start += ACTION_WIDTH) {
            double squareSum = 0.0;
            for (int i = start; i < start + ACTION_WIDTH; i++) {
                squareSum += vector[i] * vector[i];
            }

            double length = Math.max(Math.sqrt(squareSum), MIN_NORM);
            for (int i = start; i < start + ACTION_WIDTH; i++) {
                vector[i] /= length;
            }
        }
    }

    public static void normalizeSobolVector(double[] vector) {
        for (int d = 0; d < vector.length; d++) {
            vector[d] = -1 + 2 * vector[d];
        }
        normalizePolicyVector(vector);
    }

    public static double round(double quantile) {
        return Math.round(quantile * 10_000) / 10_000.0;
    }
}
