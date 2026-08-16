package calibration.statistics;

import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;

/// Tabular and pairwise correlation helpers using Apache Commons Math.
public final class CorrelationAnalysis {

    private CorrelationAnalysis() {}

    /// Calculates pairwise Pearson linear correlation coefficient between x and y.
    public static double pearson(double[] x, double[] y) {
        validatePairwiseInputs(x, y);
        return new PearsonsCorrelation().correlation(x, y);
    }

    /// Calculates pairwise Spearman rank correlation coefficient between x and y.
    public static double spearman(double[] x, double[] y) {
        validatePairwiseInputs(x, y);
        return new SpearmansCorrelation().correlation(x, y);
    }

    /// Calculates the Pearson correlation matrix for tabular data (rows = observations, columns = variables).
    public static double[][] pearsonMatrix(double[][] data) {
        validateMatrix(data);
        return new PearsonsCorrelation().computeCorrelationMatrix(data).getData();
    }

    /// Calculates the Spearman correlation matrix for tabular data (rows = observations, columns = variables).
    public static double[][] spearmanMatrix(double[][] data) {
        validateMatrix(data);
        return new SpearmansCorrelation().computeCorrelationMatrix(data).getData();
    }

    private static void validatePairwiseInputs(double[] x, double[] y) {
        if (x == null || y == null) {
            throw new NullPointerException("Input arrays must not be null");
        }
        if (x.length != y.length) {
            throw new IllegalArgumentException("Arrays must have equal length: " + x.length + " != " + y.length);
        }
        if (x.length < 2) {
            throw new IllegalArgumentException("At least 2 observations required for correlation, got: " + x.length);
        }
        for (int i = 0; i < x.length; i++) {
            if (!Double.isFinite(x[i])) {
                throw new IllegalArgumentException("x[" + i + "] must be finite, got: " + x[i]);
            }
            if (!Double.isFinite(y[i])) {
                throw new IllegalArgumentException("y[" + i + "] must be finite, got: " + y[i]);
            }
        }
    }

    private static void validateMatrix(double[][] data) {
        if (data == null) {
            throw new NullPointerException("Data matrix must not be null");
        }
        if (data.length < 2) {
            throw new IllegalArgumentException(
                    "Data matrix must have at least 2 observation rows, got: " + data.length);
        }
        int cols = data[0] != null ? data[0].length : 0;
        if (cols < 2) {
            throw new IllegalArgumentException("Data matrix must have at least 2 variable columns, got: " + cols);
        }
        for (int r = 0; r < data.length; r++) {
            if (data[r] == null || data[r].length != cols) {
                throw new IllegalArgumentException("Row " + r + " is invalid or has mismatched column count");
            }
            for (int c = 0; c < cols; c++) {
                if (!Double.isFinite(data[r][c])) {
                    throw new IllegalArgumentException("data[" + r + "][" + c + "] must be finite, got: " + data[r][c]);
                }
            }
        }
    }
}
