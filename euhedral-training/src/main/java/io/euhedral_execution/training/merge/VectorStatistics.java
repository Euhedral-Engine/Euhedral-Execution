package io.euhedral_execution.training.merge;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.merge.data.WeightedValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class VectorStatistics {

    public static double quantileType7(double[] values, double probability) {
        if (values.length == 0 || !Double.isFinite(probability) || probability < 0 || probability > 1) {
            throw new IllegalArgumentException("Invalid quantile input");
        }
        double[] sorted = values.clone();
        for (double value : sorted) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite value");
            }
        }
        Arrays.sort(sorted);
        if (sorted.length == 1) {
            return canonicalZero(sorted[0]);
        }
        double h = (sorted.length - 1) * probability;
        int index = (int) StrictMath.floor(h);
        double result = sorted[index] + (h - index) * (sorted[Math.min(index + 1, sorted.length - 1)] - sorted[index]);
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("Non-finite quantile result");
        }
        return canonicalZero(result);
    }

    public static double median(double[] values) {
        return quantileType7(values, 0.5);
    }

    public static double mad(double[] values) {
        double median = median(values);
        double[] deviations = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            deviations[i] = StrictMath.abs(values[i] - median);
        }
        return median(deviations);
    }

    public static double compensatedMean(double[] values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("Empty values");
        }
        double sum = 0;
        double correction = 0;
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite value");
            }
            double next = sum + value;
            correction += StrictMath.abs(sum) >= StrictMath.abs(value) ? (sum - next) + value : (value - next) + sum;
            sum = next;
        }
        double result = (sum + correction) / values.length;
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("Non-finite mean result");
        }
        return canonicalZero(result);
    }

    public static double weightedMedian(List<WeightedValue<PolicyId>> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Empty values");
        }
        List<WeightedValue<PolicyId>> sorted = new ArrayList<>(values);
        sorted.sort(
                Comparator.comparingDouble(WeightedValue<PolicyId>::value).thenComparing(WeightedValue::tieBreaker));
        double total = compensatedSum(
                sorted.stream().mapToDouble(WeightedValue::weight).toArray());
        double sum = 0;
        double correction = 0;
        for (WeightedValue<PolicyId> value : sorted) {
            double next = sum + value.weight();
            correction += StrictMath.abs(sum) >= StrictMath.abs(value.weight())
                    ? (sum - next) + value.weight()
                    : (value.weight() - next) + sum;
            sum = next;
            if (sum + correction >= total / 2) {
                return value.value();
            }
        }
        return sorted.getLast().value();
    }

    public static double[] capAndNormalizeWeights(double[] rawWeights, double maximumShare) {
        if (rawWeights.length == 0
                || !Double.isFinite(maximumShare)
                || maximumShare <= 0
                || maximumShare * rawWeights.length < 1) {
            throw new IllegalArgumentException("Invalid weights or cap");
        }
        double[] result = new double[rawWeights.length];
        boolean[] capped = new boolean[rawWeights.length];
        int active = rawWeights.length;
        double remaining = 1;
        while (active > 0) {
            double activeSum = 0;
            for (int i = 0; i < rawWeights.length; i++) {
                if (!Double.isFinite(rawWeights[i]) || rawWeights[i] <= 0) {
                    throw new IllegalArgumentException("Weights must be finite and positive");
                }
                if (!capped[i]) {
                    activeSum += rawWeights[i];
                }
            }
            List<Integer> newlyCapped = new ArrayList<>();
            for (int i = 0; i < rawWeights.length; i++) {
                if (!capped[i] && rawWeights[i] / activeSum * remaining > maximumShare) {
                    newlyCapped.add(i);
                }
            }
            if (newlyCapped.isEmpty()) {
                for (int i = 0; i < rawWeights.length; i++) {
                    if (!capped[i]) {
                        result[i] = rawWeights[i] / activeSum * remaining;
                    }
                }
                break;
            }
            for (int index : newlyCapped) {
                result[index] = maximumShare;
                capped[index] = true;
                remaining -= maximumShare;
                active--;
            }
        }
        double remainder = 1 - compensatedSum(result);
        for (int i = result.length - 1; i >= 0; i--) {
            if (result[i] + remainder <= Math.nextUp(maximumShare)) {
                result[i] += remainder;
                remainder = 0;
                break;
            }
        }
        if (remainder != 0
                || Arrays.stream(result)
                        .anyMatch(
                                weight -> !Double.isFinite(weight) || weight <= 0 || weight > Math.nextUp(maximumShare))
                || StrictMath.abs(compensatedSum(result) - 1.0) > 1e-14) {
            throw new IllegalStateException("Unable to cap and normalize anchor weights");
        }
        return result;
    }

    private static double compensatedSum(double[] values) {
        double sum = 0, correction = 0;
        for (double value : values) {
            double next = sum + value;
            correction += StrictMath.abs(sum) >= StrictMath.abs(value) ? (sum - next) + value : (value - next) + sum;
            sum = next;
        }
        return sum + correction;
    }

    private static double canonicalZero(double value) {
        return value == 0 ? 0.0 : value;
    }

    private VectorStatistics() {}
}
