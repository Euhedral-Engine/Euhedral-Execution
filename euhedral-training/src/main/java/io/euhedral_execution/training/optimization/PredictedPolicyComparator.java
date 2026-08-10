package io.euhedral_execution.training.optimization;

import io.euhedral_execution.training.optimization.data.PredictedPolicySummary;
import java.util.Comparator;

public final class PredictedPolicyComparator {
    public static final Comparator<PredictedPolicySummary> BEST_FIRST = (left, right) -> {
        int result = descending(left.predictedWorstQuality(), right.predictedWorstQuality());
        if (result == 0) result = descending(left.predictedQualityP25(), right.predictedQualityP25());
        if (result == 0)
            result = descending(left.predictedGeometricMeanQuality(), right.predictedGeometricMeanQuality());
        if (result == 0) result = Double.compare(left.predictedQualityMad(), right.predictedQualityMad());
        if (result == 0) result = Double.compare(left.maximumEpistemicStdDev(), right.maximumEpistemicStdDev());
        if (result == 0) result = Double.compare(left.maximumDisagreementRange(), right.maximumDisagreementRange());
        if (result == 0) result = Double.compare(left.meanOrdinalStdDev(), right.meanOrdinalStdDev());
        return result != 0
                ? result
                : left.policy().id().compareTo(right.policy().id());
    };

    public static final Comparator<PredictedPolicySummary> AUDIT_FIRST = (left, right) -> {
        int result = descending(left.maximumEpistemicStdDev(), right.maximumEpistemicStdDev());
        if (result == 0) result = descending(left.maximumDisagreementRange(), right.maximumDisagreementRange());
        if (result == 0) result = descending(left.meanOrdinalEntropy(), right.meanOrdinalEntropy());
        return result != 0 ? result : BEST_FIRST.compare(left, right);
    };

    private PredictedPolicyComparator() {}

    private static int descending(double left, double right) {
        return Double.compare(right, left);
    }
}
