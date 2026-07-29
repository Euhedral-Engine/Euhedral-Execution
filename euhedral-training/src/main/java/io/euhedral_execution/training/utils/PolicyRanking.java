package io.euhedral_execution.training.utils;

import static io.euhedral_execution.training.utils.CommonFunctions.round;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Defines the scheduler-policy ordering used by both candidate selection and ordinal labels.
 *
 * <p>Policies are ranked by high median throughput, then low inter-quartile range, then low tail
 * range. The classifier learns nine cumulative decile decisions derived only from the training
 * partition, which keeps validation and test metadata out of the label calibration step.</p>
 */
public final class PolicyRanking {

    public static final int QUANTILE_COUNT = 5;
    public static final int ORDINAL_OUTPUTS = 9;

    public static final Comparator<double[]> COMPARATOR = PolicyRanking::compare;

    public static int compare(double[] first, double[] second) {
        requireQuantiles(first);
        requireQuantiles(second);

        int median = Double.compare(round(first[2]), round(second[2]));
        if (median != 0) {
            return median;
        }

        double firstIqr = round(first[3]) - round(first[1]);
        double secondIqr = round(second[3]) - round(second[1]);
        int iqr = Double.compare(secondIqr, firstIqr);
        if (iqr != 0) {
            return iqr;
        }

        double firstTails = round(first[4]) - round(first[0]);
        double secondTails = round(second[4]) - round(second[0]);
        return Double.compare(secondTails, firstTails);
    }

    public static double[][] buildDecileThresholds(List<double[]> trainingQuantiles) {
        if (trainingQuantiles.size() < ORDINAL_OUTPUTS + 1) {
            throw new IllegalArgumentException(
                    "At least ten training samples are required for ordinal decile labels");
        }

        List<double[]> sorted = new ArrayList<>(trainingQuantiles.size());
        for (double[] quantiles : trainingQuantiles) {
            requireQuantiles(quantiles);
            sorted.add(quantiles);
        }
        sorted.sort(COMPARATOR);

        double[][] thresholds = new double[ORDINAL_OUTPUTS][];
        int size = sorted.size();
        for (int output = 0; output < ORDINAL_OUTPUTS; output++) {
            double percentile = (output + 1) / 10.0;
            // Labels use >= threshold. Selecting floor(percentile * N) therefore leaves exactly
            // N - floor(percentile * N) positive samples when all ranks are distinct.
            int index = Math.min(size - 1, (int) Math.floor(percentile * size));
            thresholds[output] = Arrays.copyOf(sorted.get(index), QUANTILE_COUNT);
        }
        return thresholds;
    }

    public static void encodeOrdinal(double[] quantiles, double[][] thresholds,
            float[] destination, int offset) {
        requireQuantiles(quantiles);
        if (thresholds.length != ORDINAL_OUTPUTS) {
            throw new IllegalArgumentException("Expected nine ordinal thresholds");
        }
        if (offset < 0 || offset + ORDINAL_OUTPUTS > destination.length) {
            throw new IndexOutOfBoundsException("Ordinal destination is too small");
        }

        for (int output = 0; output < ORDINAL_OUTPUTS; output++) {
            destination[offset + output] =
                    compare(quantiles, thresholds[output]) >= 0 ? 1.0f : 0.0f;
        }
    }

    public static double sigmoid(double logit) {
        if (logit >= 0) {
            return 1.0 / (1.0 + Math.exp(-logit));
        }
        double exp = Math.exp(logit);
        return exp / (1.0 + exp);
    }

    private static void requireQuantiles(double[] quantiles) {
        if (quantiles == null || quantiles.length != QUANTILE_COUNT) {
            throw new IllegalArgumentException("Expected [P10, P25, P50, P75, P90]");
        }
    }

    private PolicyRanking() {
    }
}
