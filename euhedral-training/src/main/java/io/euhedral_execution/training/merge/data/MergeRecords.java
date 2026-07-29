package io.euhedral_execution.training.merge.data;

import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.PolicyRole;
import io.euhedral_execution.training.merge.enums.CalibrationStatus;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public final class MergeRecords {

    private static boolean finitePositive(OptionalDouble value) {
        return value.isPresent() && Double.isFinite(value.getAsDouble())
                && value.getAsDouble() > 0;
    }

    private static boolean finiteNonNegative(OptionalDouble value) {
        return value.isPresent() && Double.isFinite(value.getAsDouble())
                && value.getAsDouble() >= 0;
    }

    private static boolean rate(OptionalDouble value) {
        return value.isPresent() && Double.isFinite(value.getAsDouble())
                && value.getAsDouble() >= 0 && value.getAsDouble() <= 1;
    }

    private MergeRecords() {
    }

    public enum RunAggregateStatus {
        VALID, INSUFFICIENT_SUCCESSES, LOW_SUCCESS_FRACTION, NONPOSITIVE_THROUGHPUT
    }

    public enum ScenarioResultStatus {
        MISSING, NO_VALID_RUN, NO_ACCEPTED_CALIBRATION, VALID_STRONG, VALID_WEAK_OVERRIDE
    }

    public record RunAggregate(PolicyVector policy, BenchmarkRunContext run,
                               SortedSet<PolicyRole> roles,
                               int plannedRepetitionCount, int successfulRepetitionCount,
                               int timeoutCount,
                               int failedCount, int skippedCount, double successRate,
                               double timeoutRate,
                               double failureRate, double nonSuccessRate, RunAggregateStatus status,
                               OptionalDouble rawP25, OptionalDouble rawMedian,
                               OptionalDouble rawP75,
                               OptionalDouble rawIqr, OptionalDouble rawLogIqr) {

        public RunAggregate {
            Objects.requireNonNull(policy);
            Objects.requireNonNull(run);
            Objects.requireNonNull(roles);
            Objects.requireNonNull(status);
            Objects.requireNonNull(rawP25);
            Objects.requireNonNull(rawMedian);
            Objects.requireNonNull(rawP75);
            Objects.requireNonNull(rawIqr);
            Objects.requireNonNull(rawLogIqr);
            TreeSet<PolicyRole> sortedRoles = new TreeSet<>(
                    java.util.Comparator.comparing(Enum::name));
            sortedRoles.addAll(roles);
            roles = Collections.unmodifiableSortedSet(sortedRoles);
            if (roles.isEmpty()) {
                throw new IllegalArgumentException("Run aggregate roles must not be empty");
            }
            if (plannedRepetitionCount != run.descriptor().parameters().expectedRepetitions()
                    || successfulRepetitionCount < 0 || timeoutCount < 0 || failedCount < 0
                    || skippedCount < 0 || successfulRepetitionCount + timeoutCount
                    + failedCount + skippedCount != plannedRepetitionCount
                    || Double.compare(successRate,
                    successfulRepetitionCount / (double) plannedRepetitionCount) != 0
                    || Double.compare(timeoutRate,
                    timeoutCount / (double) plannedRepetitionCount) != 0
                    || Double.compare(failureRate,
                    (failedCount + skippedCount) / (double) plannedRepetitionCount) != 0
                    || Double.compare(nonSuccessRate,
                    (timeoutCount + failedCount + skippedCount)
                            / (double) plannedRepetitionCount) != 0) {
                throw new IllegalArgumentException("Invalid run aggregate counts or rates");
            }
            boolean statisticsPresent = rawP25.isPresent() && rawMedian.isPresent()
                    && rawP75.isPresent() && rawIqr.isPresent() && rawLogIqr.isPresent();
            if (successfulRepetitionCount == 0 ? statisticsPresent
                    : !statisticsPresent || !finitePositive(rawP25)
                      || !finitePositive(rawMedian) || !finitePositive(rawP75)
                      || !finiteNonNegative(rawIqr) || !finiteNonNegative(rawLogIqr)
                      || rawP25.getAsDouble() > rawMedian.getAsDouble()
                      || rawMedian.getAsDouble() > rawP75.getAsDouble()
                      || Double.compare(rawIqr.getAsDouble(),
                            rawP75.getAsDouble() - rawP25.getAsDouble()) != 0) {
                throw new IllegalArgumentException("Invalid run aggregate statistics");
            }
        }
    }

    public record RunCalibration(BenchmarkRunContext run, String referenceRunId,
                                 String anchorSetId, int fixedAnchorCount, int sharedAnchorCount,
                                 OptionalDouble deltaLog, OptionalDouble scaleFactor,
                                 OptionalDouble weightedMedianAbsoluteResidual,
                                 CalibrationStatus status,
                                 String reason, SortedMap<PolicyId, Double> cappedAnchorWeights) {

        public RunCalibration {
            Objects.requireNonNull(run);
            Objects.requireNonNull(referenceRunId);
            Objects.requireNonNull(anchorSetId);
            Objects.requireNonNull(deltaLog);
            Objects.requireNonNull(scaleFactor);
            Objects.requireNonNull(weightedMedianAbsoluteResidual);
            Objects.requireNonNull(status);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(cappedAnchorWeights);
            cappedAnchorWeights = Collections.unmodifiableSortedMap(
                    new TreeMap<>(cappedAnchorWeights));
            if (!anchorSetId.matches("a1-[0-9a-f]{16}") || fixedAnchorCount < 1
                    || sharedAnchorCount < 0 || sharedAnchorCount > fixedAnchorCount
                    || reason.isEmpty() || !cappedAnchorWeights.isEmpty()
                    && cappedAnchorWeights.size() != sharedAnchorCount
                    || cappedAnchorWeights.values().stream().anyMatch(weight ->
                    !Double.isFinite(weight) || weight <= 0)) {
                throw new IllegalArgumentException("Invalid run calibration");
            }
        }
    }

    public record ScenarioResult(SourceScenario scenario, PolicyVector policy,
                                 ScenarioResultStatus status, int totalRunCount,
                                 int acceptedRunCount,
                                 int weakRunCount, int uncalibratedRunCount,
                                 int successfulRepetitionCount,
                                 int plannedRepetitionCount, OptionalDouble throughputP25,
                                 OptionalDouble throughputMedian, OptionalDouble throughputP75,
                                 OptionalDouble throughputIqr,
                                 OptionalDouble medianWithinRunRelativeIqr,
                                 OptionalDouble meanTimeoutRate, OptionalDouble meanFailureRate,
                                 OptionalDouble meanNonSuccessRate,
                                 OptionalDouble bootstrapMedianCiLow,
                                 OptionalDouble bootstrapMedianCiHigh, OptionalDouble quality) {

        public ScenarioResult {
            Objects.requireNonNull(scenario);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(status);
            Objects.requireNonNull(throughputP25);
            Objects.requireNonNull(throughputMedian);
            Objects.requireNonNull(throughputP75);
            Objects.requireNonNull(throughputIqr);
            Objects.requireNonNull(medianWithinRunRelativeIqr);
            Objects.requireNonNull(meanTimeoutRate);
            Objects.requireNonNull(meanFailureRate);
            Objects.requireNonNull(meanNonSuccessRate);
            Objects.requireNonNull(bootstrapMedianCiLow);
            Objects.requireNonNull(bootstrapMedianCiHigh);
            Objects.requireNonNull(quality);
            if (acceptedRunCount < 0 || acceptedRunCount > totalRunCount
                    || weakRunCount < 0 || uncalibratedRunCount < 0
                    || successfulRepetitionCount < 0 || plannedRepetitionCount < 0
                    || successfulRepetitionCount > plannedRepetitionCount) {
                throw new IllegalArgumentException("Invalid scenario counts");
            }
            boolean valid = status == ScenarioResultStatus.VALID_STRONG
                    || status == ScenarioResultStatus.VALID_WEAK_OVERRIDE;
            boolean numerics = throughputP25.isPresent() && throughputMedian.isPresent()
                    && throughputP75.isPresent() && throughputIqr.isPresent()
                    && medianWithinRunRelativeIqr.isPresent() && meanTimeoutRate.isPresent()
                    && meanFailureRate.isPresent() && meanNonSuccessRate.isPresent()
                    && bootstrapMedianCiLow.isPresent() && bootstrapMedianCiHigh.isPresent();
            if (valid != numerics || quality.isPresent() && !valid) {
                throw new IllegalArgumentException("Scenario status and numerics disagree");
            }
            if (valid && (!finitePositive(throughputP25) || !finitePositive(throughputMedian)
                    || !finitePositive(throughputP75) || !finiteNonNegative(throughputIqr)
                    || !finiteNonNegative(medianWithinRunRelativeIqr)
                    || !rate(meanTimeoutRate) || !rate(meanFailureRate)
                    || !rate(meanNonSuccessRate) || !finitePositive(bootstrapMedianCiLow)
                    || !finitePositive(bootstrapMedianCiHigh)
                    || quality.isPresent() && !rate(quality))) {
                throw new IllegalArgumentException("Invalid scenario numerics");
            }
        }

        public ScenarioResult withQuality(double newQuality) {
            return new ScenarioResult(scenario, policy, status, totalRunCount, acceptedRunCount,
                    weakRunCount, uncalibratedRunCount, successfulRepetitionCount,
                    plannedRepetitionCount, throughputP25, throughputMedian, throughputP75,
                    throughputIqr, medianWithinRunRelativeIqr, meanTimeoutRate, meanFailureRate,
                    meanNonSuccessRate, bootstrapMedianCiLow, bootstrapMedianCiHigh,
                    OptionalDouble.of(newQuality));
        }
    }

    public record RobustPolicySummary(PolicyVector policy, boolean eligible,
                                      int requiredScenarioCount, int observedRequiredScenarioCount,
                                      int validRequiredScenarioCount, double coverageFraction,
                                      OptionalDouble worstQuality, OptionalDouble qualityP25,
                                      OptionalDouble geometricMeanQuality,
                                      OptionalDouble crossScenarioQualityMad,
                                      OptionalDouble medianRelativeIqr,
                                      OptionalDouble meanNonSuccessRate,
                                      OptionalDouble meanTimeoutRate,
                                      SortedSet<SourceScenario> measuredScenarios,
                                      SortedSet<SourceScenario> missingScenarios,
                                      SortedSet<SourceScenario> rejectedScenarios) {

        public RobustPolicySummary {
            Objects.requireNonNull(policy);
            Objects.requireNonNull(worstQuality);
            Objects.requireNonNull(qualityP25);
            Objects.requireNonNull(geometricMeanQuality);
            Objects.requireNonNull(crossScenarioQualityMad);
            Objects.requireNonNull(medianRelativeIqr);
            Objects.requireNonNull(meanNonSuccessRate);
            Objects.requireNonNull(meanTimeoutRate);
            Objects.requireNonNull(measuredScenarios);
            Objects.requireNonNull(missingScenarios);
            Objects.requireNonNull(rejectedScenarios);
            measuredScenarios = Collections.unmodifiableSortedSet(new TreeSet<>(measuredScenarios));
            missingScenarios = Collections.unmodifiableSortedSet(new TreeSet<>(missingScenarios));
            rejectedScenarios = Collections.unmodifiableSortedSet(new TreeSet<>(rejectedScenarios));
            if (requiredScenarioCount <= 0 || !Double.isFinite(coverageFraction)
                    || coverageFraction < 0 || coverageFraction > 1) {
                throw new IllegalArgumentException("Invalid coverage");
            }
            if (validRequiredScenarioCount < 0
                    || validRequiredScenarioCount > observedRequiredScenarioCount
                    || observedRequiredScenarioCount > requiredScenarioCount
                    || Double.compare(coverageFraction,
                    validRequiredScenarioCount / (double) requiredScenarioCount) != 0
                    || eligible != (validRequiredScenarioCount == requiredScenarioCount)) {
                throw new IllegalArgumentException("Coverage counts disagree");
            }
            if (eligible && (worstQuality.isEmpty() || qualityP25.isEmpty()
                    || geometricMeanQuality.isEmpty() || crossScenarioQualityMad.isEmpty()
                    || medianRelativeIqr.isEmpty() || meanNonSuccessRate.isEmpty()
                    || meanTimeoutRate.isEmpty())) {
                throw new IllegalArgumentException("Eligible summary lacks metrics");
            }
            if (eligible && (!rate(worstQuality) || !rate(qualityP25)
                    || !rate(geometricMeanQuality) || !rate(crossScenarioQualityMad)
                    || !finiteNonNegative(medianRelativeIqr) || !rate(meanNonSuccessRate)
                    || !rate(meanTimeoutRate))) {
                throw new IllegalArgumentException("Eligible summary has invalid metrics");
            }
        }
    }

    public record MergeResult(CalibrationPlan calibrationPlan,
                              List<RunCalibration> calibrations,
                              List<ScenarioResult> scenarioResults,
                              List<RobustPolicySummary> robustSummaries) {

        public MergeResult {
            calibrations = List.copyOf(calibrations);
            scenarioResults = List.copyOf(scenarioResults);
            robustSummaries = List.copyOf(robustSummaries);
        }
    }
}
