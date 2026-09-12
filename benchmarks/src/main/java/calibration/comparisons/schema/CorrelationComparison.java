package calibration.comparisons.schema;

import calibration.statistics.iteration.CorrelationResult;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Comparison between matching baseline and candidate correlation matrices.
public record CorrelationComparison(
        @NonNull CorrelationResult baseline,
        @NonNull CorrelationResult candidate,
        String[] columnNames,
        double[][] pearsonDeltas,
        double[][] spearmanDeltas) {

    public CorrelationComparison {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        if (columnNames != null) {
            columnNames = columnNames.clone();
        } else {
            columnNames = new String[0];
        }

        if (pearsonDeltas != null) {
            double[][] copy = new double[pearsonDeltas.length][];
            for (int i = 0; i < pearsonDeltas.length; i++) {
                if (pearsonDeltas[i] != null) {
                    copy[i] = pearsonDeltas[i].clone();
                }
            }
            pearsonDeltas = copy;
        } else {
            pearsonDeltas = new double[0][0];
        }

        if (spearmanDeltas != null) {
            double[][] copy = new double[spearmanDeltas.length][];
            for (int i = 0; i < spearmanDeltas.length; i++) {
                if (spearmanDeltas[i] != null) {
                    copy[i] = spearmanDeltas[i].clone();
                }
            }
            spearmanDeltas = copy;
        } else {
            spearmanDeltas = new double[0][0];
        }
    }

    @Override
    public String[] columnNames() {
        return columnNames.clone();
    }

    @Override
    public double[][] pearsonDeltas() {
        double[][] copy = new double[pearsonDeltas.length][];
        for (int i = 0; i < pearsonDeltas.length; i++) {
            copy[i] = pearsonDeltas[i].clone();
        }
        return copy;
    }

    @Override
    public double[][] spearmanDeltas() {
        double[][] copy = new double[spearmanDeltas.length][];
        for (int i = 0; i < spearmanDeltas.length; i++) {
            copy[i] = spearmanDeltas[i].clone();
        }
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CorrelationComparison that)) return false;
        return baseline.equals(that.baseline)
                && candidate.equals(that.candidate)
                && Arrays.equals(columnNames, that.columnNames)
                && Arrays.deepEquals(pearsonDeltas, that.pearsonDeltas)
                && Arrays.deepEquals(spearmanDeltas, that.spearmanDeltas);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(baseline, candidate);
        result = 31 * result + Arrays.hashCode(columnNames);
        result = 31 * result + Arrays.deepHashCode(pearsonDeltas);
        result = 31 * result + Arrays.deepHashCode(spearmanDeltas);
        return result;
    }
}
