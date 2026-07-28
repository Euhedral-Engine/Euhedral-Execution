package io.euhedral_execution.training.learning;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.MergeRecords.ScenarioResultStatus;
import java.util.Objects;

public record ScenarioLearningRow(PolicyVector policy, SourceScenario scenario,
        ScenarioResultStatus sourceStatus, double quality, double throughputMedian,
        double bootstrapMedianCiLow, double bootstrapMedianCiHigh, int acceptedRunCount,
        double medianWithinRunRelativeIqr, double meanNonSuccessRate)
        implements Comparable<ScenarioLearningRow> {
    public ScenarioLearningRow {
        Objects.requireNonNull(policy); Objects.requireNonNull(scenario);
        Objects.requireNonNull(sourceStatus);
        if (sourceStatus != ScenarioResultStatus.VALID_STRONG
                && sourceStatus != ScenarioResultStatus.VALID_WEAK_OVERRIDE)
            throw new IllegalArgumentException("Learning rows require a valid Phase 1 status");
        if (!rate(quality) || !positive(throughputMedian) || !positive(bootstrapMedianCiLow)
                || !positive(bootstrapMedianCiHigh) || bootstrapMedianCiLow > bootstrapMedianCiHigh
                || acceptedRunCount <= 0 || !nonnegative(medianWithinRunRelativeIqr)
                || !rate(meanNonSuccessRate))
            throw new IllegalArgumentException("Invalid scenario learning row");
    }
    private static boolean rate(double x) { return Double.isFinite(x) && x >= 0 && x <= 1; }
    private static boolean positive(double x) { return Double.isFinite(x) && x > 0; }
    private static boolean nonnegative(double x) { return Double.isFinite(x) && x >= 0; }
    @Override public int compareTo(ScenarioLearningRow other) {
        int c = policy.id().compareTo(other.policy.id());
        return c != 0 ? c : scenario.compareTo(other.scenario);
    }
}
