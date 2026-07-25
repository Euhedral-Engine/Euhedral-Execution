package io.euhedral_execution.training.utils;

public class CommonFunctions {

    public static void normalize(double[] array) {
        double squareSum = 0;
        for(double d : array) {
            squareSum += d * d;
        }

        double length = Math.sqrt(squareSum);
        for(int i = 0; i < array.length; i++) {
            array[i] /= length;
        }
    }

    public static void normalizeSobolVector(double[] vector) {
        for (int d = 0; d < vector.length; d++) {
            vector[d] = -1 + 2 * vector[d];
        }

        int count = 0;
        while (count < vector.length) {
            double squareSum = 0.0;
            for (int i = count; i < count + 7; i++) {
                squareSum += vector[i] * vector[i];
            }

            double length = Math.sqrt(squareSum);
            for (int i = count; i < count + 7; i++) {
                vector[i] /= length;
            }
            count += 7;
        }
    }

    public static double round(double quantile) {
        return Math.round(quantile * 10_000) / 10_000.0;
    }
}
