package calibration.statistics.iteration;

import calibration.statistics.CorrelationAnalysis;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/// Retains Pearson and Spearman correlation matrices for aligned multi-variable observations.
public record CorrelationResult(String[] columnNames, double[][] pearsonMatrix, double[][] spearmanMatrix) {

    public static final CorrelationResult EMPTY =
            new CorrelationResult(new String[0], new double[0][0], new double[0][0]);

    public CorrelationResult {
        if (columnNames != null) {
            columnNames = columnNames.clone();
        } else {
            columnNames = new String[0];
        }
        if (pearsonMatrix != null) {
            double[][] copy = new double[pearsonMatrix.length][];
            for (int i = 0; i < pearsonMatrix.length; i++) {
                if (pearsonMatrix[i] != null) {
                    copy[i] = pearsonMatrix[i].clone();
                }
            }
            pearsonMatrix = copy;
        } else {
            pearsonMatrix = new double[0][0];
        }
        if (spearmanMatrix != null) {
            double[][] copy = new double[spearmanMatrix.length][];
            for (int i = 0; i < spearmanMatrix.length; i++) {
                if (spearmanMatrix[i] != null) {
                    copy[i] = spearmanMatrix[i].clone();
                }
            }
            spearmanMatrix = copy;
        } else {
            spearmanMatrix = new double[0][0];
        }
    }

    public static CorrelationResult empty() {
        return EMPTY;
    }

    public static CorrelationResult empty(String @Nullable [] columnNames) {
        if (columnNames == null || columnNames.length == 0) {
            return EMPTY;
        }
        int n = columnNames.length;
        double[][] p = new double[n][n];
        double[][] s = new double[n][n];
        for (int i = 0; i < n; i++) {
            Arrays.fill(p[i], Double.NaN);
            Arrays.fill(s[i], Double.NaN);
        }
        return new CorrelationResult(columnNames.clone(), p, s);
    }

    public static CorrelationResult of(String @Nullable [] columnNames, double @Nullable [][] data) {
        if (columnNames == null || data == null || data.length < 2 || columnNames.length < 2) {
            return empty(columnNames != null ? columnNames : new String[0]);
        }
        try {
            double[][] p = CorrelationAnalysis.pearsonMatrix(data);
            double[][] s = CorrelationAnalysis.spearmanMatrix(data);
            return new CorrelationResult(columnNames.clone(), p, s);
        } catch (Exception e) {
            return empty(columnNames);
        }
    }

    @Override
    public String[] columnNames() {
        return columnNames.clone();
    }

    @Override
    public double[][] pearsonMatrix() {
        double[][] copy = new double[pearsonMatrix.length][];
        for (int i = 0; i < pearsonMatrix.length; i++) {
            copy[i] = pearsonMatrix[i].clone();
        }
        return copy;
    }

    @Override
    public double[][] spearmanMatrix() {
        double[][] copy = new double[spearmanMatrix.length][];
        for (int i = 0; i < spearmanMatrix.length; i++) {
            copy[i] = spearmanMatrix[i].clone();
        }
        return copy;
    }

    public boolean isEmpty() {
        return columnNames.length == 0
                || pearsonMatrix.length == 0
                || (pearsonMatrix.length > 0 && pearsonMatrix[0].length > 0 && Double.isNaN(pearsonMatrix[0][0]));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CorrelationResult that)) return false;
        return Arrays.equals(columnNames, that.columnNames)
                && Arrays.deepEquals(pearsonMatrix, that.pearsonMatrix)
                && Arrays.deepEquals(spearmanMatrix, that.spearmanMatrix);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(columnNames);
        result = 31 * result + Arrays.deepHashCode(pearsonMatrix);
        result = 31 * result + Arrays.deepHashCode(spearmanMatrix);
        return result;
    }
}
