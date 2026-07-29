package io.euhedral_execution.training.learning;

import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.policies;
import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.scenarios;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.EvaluationThresholds;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.data.PolicyPredictionCurve;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.learning.enums.EvaluationStatus;
import io.euhedral_execution.training.learning.enums.FeatureSelectionMode;
import io.euhedral_execution.training.learning.enums.ModelAcceptanceStatus;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningRow;
import io.euhedral_execution.training.learning.metadata.FeatureSelectionDecision;
import io.euhedral_execution.training.learning.output.EvaluationSummary;
import io.euhedral_execution.training.learning.statistics.AblationMetric;
import io.euhedral_execution.training.learning.statistics.ScenarioEvaluationMetrics;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class ScenarioModelEvaluatorTest {
    @Test
    void computesExactErrorsCoverageAndUncertainty() {
        SourceScenario scenario = scenarios().first();
        List<PolicyVector> policies = policies(10);
        ArrayList<ScenarioLearningRow> rows = new ArrayList<>();
        ArrayList<PolicyPredictionCurve> predictions = new ArrayList<>();
        for (int index = 0; index < policies.size(); index++) {
            double actual = index / 9.0;
            rows.add(row(policies.get(index), scenario, actual));
            predictions.add(curve(policies.get(index), scenario, actual + (index % 2 == 0
                    ? 0.05 : -0.05), 0.02, actual - 0.1, actual + 0.1));
        }
        EvaluationSummary result = ScenarioModelEvaluator.evaluate(
                "GROUPED_TEST", ScenarioFeatureSet.RATIO_ONLY, rows, predictions);
        ScenarioEvaluationMetrics metric = result.scenarios().getFirst();
        assertThat(metric.mae()).isCloseTo(0.05, within(1.0e-15));
        assertThat(metric.rmse()).isCloseTo(0.05, within(1.0e-15));
        assertThat(metric.meanBias()).isCloseTo(0, within(1.0e-15));
        assertThat(metric.intervalCoverage95()).isOne();
        assertThat(metric.meanEpistemicStdDev()).isEqualTo(0.02);
        assertThat(metric.selectedCount()).isOne();
        assertThat(metric.actualTopDecileCount()).isOne();
        assertThat(metric.status()).isEqualTo(EvaluationStatus.OK);
    }

    @Test
    void exactTiesUseMidranksAndConstantPredictionHasBlankSpearman() {
        SourceScenario scenario = scenarios().first();
        List<PolicyVector> policies = policies(4);
        List<ScenarioLearningRow> rows = List.of(
                row(policies.get(0), scenario, 0),
                row(policies.get(1), scenario, 0.5),
                row(policies.get(2), scenario, 0.5),
                row(policies.get(3), scenario, 1));
        List<PolicyPredictionCurve> tied = List.of(
                curve(policies.get(0), scenario, 0.1, 0, 0.05, 0.15),
                curve(policies.get(1), scenario, 0.5, 0, 0.4, 0.6),
                curve(policies.get(2), scenario, 0.5, 0, 0.4, 0.6),
                curve(policies.get(3), scenario, 0.9, 0, 0.8, 0.95));
        assertThat(ScenarioModelEvaluator.evaluate("test", ScenarioFeatureSet.RATIO_ONLY,
                rows, tied).macroSpearman()).hasValue(1);
        List<PolicyPredictionCurve> constant = policies.stream()
                .map(policy -> curve(policy, scenario, 0.5, 0, 0.05, 0.95)).toList();
        ScenarioEvaluationMetrics metric = ScenarioModelEvaluator.evaluate(
                "test", ScenarioFeatureSet.RATIO_ONLY, rows, constant)
                .scenarios().getFirst();
        assertThat(metric.spearman()).isEmpty();
        assertThat(metric.status()).isEqualTo(EvaluationStatus.CONSTANT_RANK);
    }

    @Test
    void macroGivesEachScenarioOneVoteAndInputPermutationIsStable() {
        List<SourceScenario> scenarioList = new ArrayList<>(scenarios());
        SourceScenario first = scenarioList.get(0);
        SourceScenario second = scenarioList.get(1);
        List<PolicyVector> policies = policies(20);
        ArrayList<ScenarioLearningRow> rows = new ArrayList<>();
        ArrayList<PolicyPredictionCurve> curves = new ArrayList<>();
        for (int index = 0; index < policies.size(); index++) {
            ArrayList<ScenarioPrediction> predictions = new ArrayList<>();
            double quality = index / 19.0;
            rows.add(row(policies.get(index), first, quality));
            predictions.add(prediction(first, quality, 0));
            if (index < 10) {
                double secondQuality = index / 9.0;
                rows.add(row(policies.get(index), second, secondQuality));
                predictions.add(prediction(second, 1 - secondQuality, 0));
            }
            curves.add(new PolicyPredictionCurve(policies.get(index), predictions));
        }
        EvaluationSummary original = ScenarioModelEvaluator.evaluate(
                "test", ScenarioFeatureSet.RATIO_ONLY, rows, curves);
        java.util.Collections.shuffle(rows, new java.util.Random(1));
        java.util.Collections.shuffle(curves, new java.util.Random(2));
        EvaluationSummary shuffled = ScenarioModelEvaluator.evaluate(
                "test", ScenarioFeatureSet.RATIO_ONLY, rows, curves);
        assertThat(shuffled).isEqualTo(original);
        assertThat(original.macroMae().orElseThrow()).isEqualTo(
                (original.scenarios().get(0).mae() + original.scenarios().get(1).mae()) / 2);
    }

    @Test
    void topSelectionUsesUncertaintyThenUnsignedPolicyIdentity() {
        SourceScenario scenario = scenarios().first();
        List<PolicyVector> policies = policies(10);
        PolicyVector smallest = policies.stream()
                .min(java.util.Comparator.comparing(PolicyVector::id)).orElseThrow();
        ArrayList<ScenarioLearningRow> rows = new ArrayList<>();
        ArrayList<PolicyPredictionCurve> curves = new ArrayList<>();
        for (int index = 0; index < policies.size(); index++) {
            PolicyVector policy = policies.get(index);
            double actual = policy.id().equals(smallest.id()) ? 0.9 : index / 20.0;
            rows.add(row(policy, scenario, actual));
            curves.add(curve(policy, scenario, 0.5, 0, 0, 1));
        }
        ScenarioEvaluationMetrics byIdentity = ScenarioModelEvaluator.evaluate(
                "test", ScenarioFeatureSet.RATIO_ONLY, rows, curves)
                .scenarios().getFirst();
        assertThat(byIdentity.selectedCount()).isOne();
        assertThat(byIdentity.precisionAtTen()).hasValue(1);

        curves = new ArrayList<>();
        for (PolicyVector policy : policies) {
            curves.add(curve(policy, scenario, 0.5,
                    policy.id().equals(smallest.id()) ? 0 : 0.1, 0, 1));
        }
        assertThat(ScenarioModelEvaluator.evaluate("test", ScenarioFeatureSet.RATIO_ONLY,
                rows, curves).scenarios().getFirst().precisionAtTen()).hasValue(1);
    }

    @Test
    void sparseScoringRowsExplicitlyDiscardUnmeasuredCurveEntries() {
        List<SourceScenario> scenarioList = new ArrayList<>(scenarios());
        SourceScenario first = scenarioList.get(0);
        SourceScenario second = scenarioList.get(1);
        List<PolicyVector> policies = policies(2);
        List<ScenarioLearningRow> rows = List.of(
                row(policies.get(0), first, 0.1),
                row(policies.get(0), second, 0.9),
                row(policies.get(1), first, 0.9));
        List<PolicyPredictionCurve> curves = List.of(
                new PolicyPredictionCurve(policies.get(0), List.of(
                        prediction(first, 0.1, 0),
                        prediction(second, 0.9, 0))),
                new PolicyPredictionCurve(policies.get(1), List.of(
                        prediction(first, 0.9, 0),
                        prediction(second, 0.1, 0))));
        List<PolicyPredictionCurve> retained =
                ScenarioFoldRunner.retainPredictionsForRows(curves, rows);
        assertThat(retained.get(1).scenarios()).hasSize(1);
        assertThat(ScenarioModelEvaluator.evaluate("test", ScenarioFeatureSet.RATIO_ONLY,
                rows, retained).scenarios()).hasSize(2);
        assertThatThrownBy(() -> ScenarioModelEvaluator.evaluate(
                "test", ScenarioFeatureSet.RATIO_ONLY, rows, curves))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Extra predictions");
    }

    @Test
    void everyAcceptanceThresholdIsInclusiveAtItsExactBoundary() {
        ScenarioTrainingConfig config = ScenarioTrainingConfig.defaults();
        EvaluationThresholds thresholds = config.thresholds();
        EvaluationSummary grouped = acceptanceSummary("GROUPED_TEST",
                thresholds.maximumGroupedMacroMae(),
                thresholds.minimumGroupedMacroSpearman(),
                thresholds.minimumGroupedMacroPrecisionAtTen(),
                thresholds.maximumGroupedMacroMae());
        EvaluationSummary loso = acceptanceSummary("TEST_LOSO",
                thresholds.maximumLosoMacroMae(),
                thresholds.minimumLosoMacroSpearman(), 1,
                thresholds.maximumLosoWorstScenarioMae());
        ScenarioAblationPlanner.Decision ablation = passingAblation();
        assertThat(ScenarioModelTrainer.acceptance(
                ablation, grouped, loso, config).status())
                .isEqualTo(ModelAcceptanceStatus.ACCEPTED);

        assertThat(ScenarioModelTrainer.acceptance(ablation,
                acceptanceSummary("GROUPED_TEST",
                        Math.nextDown(thresholds.maximumGroupedMacroMae()),
                        Math.nextUp(thresholds.minimumGroupedMacroSpearman()),
                        Math.nextUp(thresholds.minimumGroupedMacroPrecisionAtTen()),
                        thresholds.maximumGroupedMacroMae()),
                loso, config).status()).isEqualTo(ModelAcceptanceStatus.ACCEPTED);
        assertThat(ScenarioModelTrainer.acceptance(ablation, grouped,
                acceptanceSummary("TEST_LOSO",
                        Math.nextDown(thresholds.maximumLosoMacroMae()),
                        Math.nextUp(thresholds.minimumLosoMacroSpearman()), 1,
                        Math.nextDown(thresholds.maximumLosoWorstScenarioMae())),
                config).status()).isEqualTo(ModelAcceptanceStatus.ACCEPTED);

        assertThat(ScenarioModelTrainer.acceptance(ablation,
                acceptanceSummary("GROUPED_TEST",
                        Math.nextUp(thresholds.maximumGroupedMacroMae()),
                        thresholds.minimumGroupedMacroSpearman(),
                        thresholds.minimumGroupedMacroPrecisionAtTen(),
                        thresholds.maximumGroupedMacroMae()),
                loso, config).status())
                .isEqualTo(ModelAcceptanceStatus.GROUPED_QUALITY_GATE_FAILED);
        assertThat(ScenarioModelTrainer.acceptance(ablation,
                acceptanceSummary("GROUPED_TEST",
                        thresholds.maximumGroupedMacroMae(),
                        Math.nextDown(thresholds.minimumGroupedMacroSpearman()),
                        thresholds.minimumGroupedMacroPrecisionAtTen(),
                        thresholds.maximumGroupedMacroMae()),
                loso, config).status())
                .isEqualTo(ModelAcceptanceStatus.GROUPED_QUALITY_GATE_FAILED);
        assertThat(ScenarioModelTrainer.acceptance(ablation,
                acceptanceSummary("GROUPED_TEST",
                        thresholds.maximumGroupedMacroMae(),
                        thresholds.minimumGroupedMacroSpearman(),
                        Math.nextDown(thresholds.minimumGroupedMacroPrecisionAtTen()),
                        thresholds.maximumGroupedMacroMae()),
                loso, config).status())
                .isEqualTo(ModelAcceptanceStatus.GROUPED_QUALITY_GATE_FAILED);
        assertThat(ScenarioModelTrainer.acceptance(ablation, grouped,
                acceptanceSummary("TEST_LOSO",
                        Math.nextUp(thresholds.maximumLosoMacroMae()),
                        thresholds.minimumLosoMacroSpearman(), 1,
                        thresholds.maximumLosoWorstScenarioMae()), config).status())
                .isEqualTo(ModelAcceptanceStatus.LOSO_QUALITY_GATE_FAILED);
        assertThat(ScenarioModelTrainer.acceptance(ablation, grouped,
                acceptanceSummary("TEST_LOSO",
                        thresholds.maximumLosoMacroMae(),
                        Math.nextDown(thresholds.minimumLosoMacroSpearman()), 1,
                        thresholds.maximumLosoWorstScenarioMae()), config).status())
                .isEqualTo(ModelAcceptanceStatus.LOSO_QUALITY_GATE_FAILED);
        assertThat(ScenarioModelTrainer.acceptance(ablation, grouped,
                acceptanceSummary("TEST_LOSO",
                        thresholds.maximumLosoMacroMae(),
                        thresholds.minimumLosoMacroSpearman(), 1,
                        Math.nextUp(thresholds.maximumLosoWorstScenarioMae())), config).status())
                .isEqualTo(ModelAcceptanceStatus.LOSO_QUALITY_GATE_FAILED);
    }

    private static EvaluationSummary acceptanceSummary(String kind, double mae,
            double spearman, double precision, double worst) {
        SourceScenario scenario = scenarios().first();
        ScenarioEvaluationMetrics metric = new ScenarioEvaluationMetrics(kind, "all",
                ScenarioFeatureSet.RATIO_ONLY, scenario, 10, 10, mae, mae, 0,
                OptionalDouble.of(spearman), 1, 1, OptionalDouble.of(precision),
                OptionalDouble.of(1), 0.2, 1, 0, 0, EvaluationStatus.OK);
        return new EvaluationSummary(kind, ScenarioFeatureSet.RATIO_ONLY, List.of(metric),
                OptionalDouble.of(mae), OptionalDouble.of(mae),
                OptionalDouble.of(spearman), OptionalDouble.of(precision),
                OptionalDouble.of(1), OptionalDouble.of(worst), OptionalDouble.of(mae));
    }

    private static ScenarioAblationPlanner.Decision passingAblation() {
        AblationMetric metric = new AblationMetric("VALIDATION_CONTEXT_GATE", "all",
                ScenarioFeatureSet.RATIO_ONLY, ScenarioFeatureSet.POLICY_ONLY,
                "all", 10, OptionalDouble.of(0.19), OptionalDouble.of(0.5),
                OptionalDouble.of(-0.01), OptionalDouble.of(0), true,
                "PASS", "CONTEXT_VALIDATED");
        FeatureSelectionDecision selection = new FeatureSelectionDecision(
                FeatureSelectionMode.RATIO_ONLY, ScenarioFeatureSet.RATIO_ONLY,
                List.of(metric), "RATIO_ONLY_REQUESTED");
        return new ScenarioAblationPlanner.Decision(selection, true, true);
    }

    private static ScenarioLearningRow row(PolicyVector policy, SourceScenario scenario,
            double quality) {
        return new ScenarioLearningRow(policy, scenario, ScenarioResultStatus.VALID_STRONG,
                quality, 10, 9, 11, 1, 0.1, 0);
    }

    private static PolicyPredictionCurve curve(PolicyVector policy, SourceScenario scenario,
            double prediction, double epistemic, double low, double high) {
        return new PolicyPredictionCurve(policy,
                List.of(new ScenarioPrediction(scenario, clamp(prediction), 0.1, clamp(low),
                        clamp(high), 0.5, 0.1, epistemic, epistemic * 2)));
    }

    private static ScenarioPrediction prediction(SourceScenario scenario, double prediction,
            double epistemic) {
        return new ScenarioPrediction(scenario, clamp(prediction), 0.1, 0.05, 0.95,
                0.5, 0.1, epistemic, epistemic * 2);
    }

    private static double clamp(double value) {
        return StrictMath.max(0, StrictMath.min(1, value));
    }
}
