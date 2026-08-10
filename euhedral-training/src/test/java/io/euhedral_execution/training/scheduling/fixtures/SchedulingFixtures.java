package io.euhedral_execution.training.scheduling.fixtures;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.data.PolicyPredictionCurve;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.merge.data.AnchorCatalog;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.merge.data.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import io.euhedral_execution.training.merge.data.ReferenceRunCatalog;
import io.euhedral_execution.training.optimization.PolicyCurvePredictor;
import io.euhedral_execution.training.optimization.PredictedPolicyRanker;
import io.euhedral_execution.training.optimization.data.PredictedPolicySummary;
import io.euhedral_execution.training.scheduling.data.OptimizationCorpusView;
import io.euhedral_execution.training.utils.CommonFunctions;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public final class SchedulingFixtures {
    public static final SourceScenario S1 = SourceScenario.of("env-a", 1, 4);
    public static final SourceScenario S2 = SourceScenario.of("env-a", 2, 4);
    public static final SourceScenario S3 = SourceScenario.of("env-a", 4, 4);
    public static final SortedSet<SourceScenario> SCENARIOS =
            java.util.Collections.unmodifiableSortedSet(new TreeSet<>(List.of(S1, S2, S3)));

    private SchedulingFixtures() {}

    public static PolicyVector policy(int seed) {
        double[] weights = new double[PolicyVector.WIDTH];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = StrictMath.sin((seed + 1.0) * (i + 1.0));
        }
        CommonFunctions.normalizePolicyVector(weights);
        return PolicyVector.of(weights);
    }

    public static PredictedPolicySummary prediction(PolicyVector policy, double... qualities) {
        if (qualities.length != SCENARIOS.size()) {
            throw new IllegalArgumentException();
        }
        ArrayList<ScenarioPrediction> rows = new ArrayList<>();
        int index = 0;
        for (SourceScenario scenario : SCENARIOS) {
            double quality = qualities[index++];
            rows.add(new ScenarioPrediction(
                    scenario,
                    quality,
                    0.05,
                    StrictMath.max(0, quality - 0.1),
                    StrictMath.min(1, quality + 0.1),
                    0.25,
                    quality,
                    0.03,
                    0.06));
        }
        return PredictedPolicyRanker.summarize(new PolicyPredictionCurve(policy, rows), SCENARIOS);
    }

    public static PolicyCurvePredictor predictor() {
        return policies -> policies.stream()
                .map(policy -> {
                    double base = 0.35 + (Long.remainderUnsigned(policy.id().value(), 50) / 100.0);
                    return prediction(
                            policy, base, StrictMath.min(0.99, base + 0.05), StrictMath.min(0.99, base + 0.10));
                })
                .toList();
    }

    public static RobustPolicySummary eligible(PolicyVector policy, double quality) {
        return new RobustPolicySummary(
                policy,
                true,
                SCENARIOS.size(),
                SCENARIOS.size(),
                SCENARIOS.size(),
                1.0,
                OptionalDouble.of(quality),
                OptionalDouble.of(quality),
                OptionalDouble.of(quality),
                OptionalDouble.of(0.0),
                OptionalDouble.of(0.05),
                OptionalDouble.of(0.0),
                OptionalDouble.of(0.0),
                SCENARIOS,
                new TreeSet<>(),
                new TreeSet<>());
    }

    public static RobustPolicySummary incomplete(PolicyVector policy, SortedSet<SourceScenario> measured) {
        TreeSet<SourceScenario> missing = new TreeSet<>(SCENARIOS);
        missing.removeAll(measured);
        return new RobustPolicySummary(
                policy,
                false,
                SCENARIOS.size(),
                measured.size(),
                measured.size(),
                measured.size() / (double) SCENARIOS.size(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                measured,
                missing,
                new TreeSet<>());
    }

    public static OptimizationCorpusView corpus(List<RobustPolicySummary> summaries) {
        TreeMap<PolicyId, PolicyVector> policies = new TreeMap<>();
        TreeMap<PolicyId, RobustPolicySummary> byId = new TreeMap<>();
        TreeMap<PolicyId, SortedMap<SourceScenario, ScenarioResultStatus>> coverage = new TreeMap<>();
        for (RobustPolicySummary summary : summaries) {
            policies.put(summary.policy().id(), summary.policy());
            byId.put(summary.policy().id(), summary);
            TreeMap<SourceScenario, ScenarioResultStatus> rows = new TreeMap<>();
            for (SourceScenario scenario : SCENARIOS) {
                rows.put(
                        scenario,
                        summary.measuredScenarios().contains(scenario)
                                ? ScenarioResultStatus.VALID_STRONG
                                : ScenarioResultStatus.MISSING);
            }
            coverage.put(summary.policy().id(), rows);
        }
        return new OptimizationCorpusView(
                policies,
                summaries.stream().filter(RobustPolicySummary::eligible).toList(),
                byId,
                coverage,
                "1".repeat(64));
    }

    public static CalibrationPlan calibration(List<PolicyVector> anchors) {
        AnchorCatalog catalog = AnchorCatalog.of(anchors);
        TreeMap<SourceScenario, String> references = new TreeMap<>();
        int index = 1;
        for (SourceScenario scenario : SCENARIOS) {
            references.put(scenario, "reference-" + index++);
        }
        return new CalibrationPlan(catalog, new ReferenceRunCatalog(1, catalog.anchorSetId(), references));
    }
}
