package calibration.comparisons;

import calibration.comparisons.schema.ComparisonCompatibility;
import calibration.comparisons.schema.CompatibilityStatus;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.ConfigurationDifference;
import calibration.comparisons.schema.DifferenceCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Analyzes compatibility between baseline and candidate completed calibration runs.
public final class ComparisonCompatibilityAnalyzer {

    private ComparisonCompatibilityAnalyzer() {}

    public static @NonNull ComparisonCompatibility analyze(
            @NonNull CompletedRun baseline, @NonNull CompletedRun candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        List<ConfigurationDifference> differences =
                TrialConfigDiffer.diff(baseline.trialConfig(), candidate.trialConfig());

        List<String> incompatibleReasons = new ArrayList<>();
        List<String> partialReasons = new ArrayList<>();

        String baseUnit = baseline.throughput().scoreUnit();
        String candUnit = candidate.throughput().scoreUnit();
        if (!baseUnit.equals(candUnit)) {
            incompatibleReasons.add("Throughput unit mismatch: baseline unit is '" + baseUnit + "', candidate unit is '"
                    + candUnit + "'");
        }

        for (ConfigurationDifference diff : differences) {
            DifferenceCategory category = diff.category();
            switch (category) {
                case ACTUATOR ->
                    incompatibleReasons.add("CACHE actuator differs at " + diff.path()
                            + " (baseline=" + diff.baselineValue()
                            + ", candidate=" + diff.candidateValue() + ")");
                case LIFECYCLE ->
                    incompatibleReasons.add("Calibration lifecycle differs at " + diff.path()
                            + " (baseline=" + diff.baselineValue()
                            + ", candidate=" + diff.candidateValue() + ")");
                case WORKLOAD ->
                    partialReasons.add("Workload configuration differs at " + diff.path()
                            + " (baseline=" + diff.baselineValue()
                            + ", candidate=" + diff.candidateValue() + ")");
                case JMH ->
                    partialReasons.add("JMH execution configuration differs at " + diff.path()
                            + " (baseline=" + diff.baselineValue()
                            + ", candidate=" + diff.candidateValue() + ")");
                case JVM ->
                    partialReasons.add("JVM argument configuration differs at " + diff.path()
                            + " (baseline=" + diff.baselineValue()
                            + ", candidate=" + diff.candidateValue() + ")");
                case HARNESS ->
                    partialReasons.add("Harness configuration differs at " + diff.path()
                            + " (baseline=" + diff.baselineValue()
                            + ", candidate=" + diff.candidateValue() + ")");
                case OBSERVATION ->
                    partialReasons.add("Observation configuration differs at " + diff.path()
                            + " (baseline=" + diff.baselineValue()
                            + ", candidate=" + diff.candidateValue()
                            + "); diagnostic telemetry comparison may be partially unavailable");
                case POLICY, IDENTITY -> {
                    // Expected or non-interfering differences for calibration comparison
                }
            }
        }

        if (!incompatibleReasons.isEmpty()) {
            return ComparisonCompatibility.incompatible(differences, incompatibleReasons);
        }

        if (!partialReasons.isEmpty()) {
            return ComparisonCompatibility.partial(differences, partialReasons);
        }

        return new ComparisonCompatibility(CompatibilityStatus.COMPATIBLE, differences, List.of());
    }
}
