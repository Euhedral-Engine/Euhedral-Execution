package io.euhedral_execution.training.merge;

import io.euhedral_execution.training.merge.MergeRecords.RobustPolicySummary;
import java.util.Comparator;

public final class RobustPolicyComparator {
    public static final Comparator<RobustPolicySummary> BEST_FIRST = (left, right) -> {
        requireEligible(left);
        requireEligible(right);
        int result = compareDescending(left.worstQuality().getAsDouble(),
                right.worstQuality().getAsDouble());
        if (result == 0) result = compareDescending(left.qualityP25().getAsDouble(),
                right.qualityP25().getAsDouble());
        if (result == 0) result = compareDescending(left.geometricMeanQuality().getAsDouble(),
                right.geometricMeanQuality().getAsDouble());
        if (result == 0) result = Double.compare(left.crossScenarioQualityMad().getAsDouble(),
                right.crossScenarioQualityMad().getAsDouble());
        if (result == 0) result = Double.compare(left.medianRelativeIqr().getAsDouble(),
                right.medianRelativeIqr().getAsDouble());
        if (result == 0) result = Double.compare(left.meanNonSuccessRate().getAsDouble(),
                right.meanNonSuccessRate().getAsDouble());
        return result != 0 ? result : left.policy().id().compareTo(right.policy().id());
    };

    public static final Comparator<RobustPolicySummary> PUBLISHED_ORDER = (left, right) -> {
        if (left.eligible() != right.eligible()) return left.eligible() ? -1 : 1;
        if (left.eligible()) return BEST_FIRST.compare(left, right);
        int result = Integer.compare(right.validRequiredScenarioCount(),
                left.validRequiredScenarioCount());
        if (result == 0) result = Integer.compare(right.observedRequiredScenarioCount(),
                left.observedRequiredScenarioCount());
        return result != 0 ? result : left.policy().id().compareTo(right.policy().id());
    };

    private static int compareDescending(double left, double right) {
        return Double.compare(right, left);
    }

    private static void requireEligible(RobustPolicySummary summary) {
        if (!summary.eligible()) throw new IllegalArgumentException("Summary is incomplete");
    }

    private RobustPolicyComparator() {
    }
}
