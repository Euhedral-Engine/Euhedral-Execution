package io.euhedral_execution.training.learning;

import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.scenarios;
import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.EvaluationThresholds;
import io.euhedral_execution.training.learning.config.ScenarioMemberSeeds;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.enums.EvaluationStatus;
import io.euhedral_execution.training.learning.enums.FeatureSelectionMode;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.output.EvaluationSummary;
import io.euhedral_execution.training.learning.statistics.ScenarioEvaluationMetrics;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class ScenarioAblationPlanTest {
    private static EvaluationSummary summary(
            String fold, ScenarioFeatureSet featureSet, double mae, double spearman, double worst) {
        SourceScenario scenario = scenarios().first();
        ScenarioEvaluationMetrics metric = new ScenarioEvaluationMetrics(
                "validation",
                fold,
                featureSet,
                scenario,
                10,
                10,
                mae,
                mae,
                0,
                OptionalDouble.of(spearman),
                1,
                1,
                OptionalDouble.of(1),
                OptionalDouble.of(1),
                0.2,
                1,
                0,
                0,
                EvaluationStatus.OK);
        return new EvaluationSummary(
                "validation",
                featureSet,
                List.of(metric),
                OptionalDouble.of(mae),
                OptionalDouble.of(mae),
                OptionalDouble.of(spearman),
                OptionalDouble.of(1),
                OptionalDouble.of(1),
                OptionalDouble.of(worst),
                OptionalDouble.of(mae));
    }

    @Test
    void exactContextAndCountBoundariesPassWithoutSelectingPolicyOnly() {
        EvaluationThresholds thresholds = EvaluationThresholds.defaults();
        EvaluationSummary policy = summary("scenario", ScenarioFeatureSet.POLICY_ONLY, 0.20, 0.50, 0.20);
        EvaluationSummary ratio = summary(
                "scenario",
                ScenarioFeatureSet.RATIO_ONLY,
                0.20 - thresholds.minimumContextMaeImprovement(),
                0.50 - thresholds.maximumContextSpearmanRegression(),
                0.20);
        EvaluationSummary environmentRatio = summary("host-a", ScenarioFeatureSet.RATIO_ONLY, 0.20, 0.50, 0.20);
        EvaluationSummary counts = summary(
                "host-a",
                ScenarioFeatureSet.RATIO_AND_COUNTS,
                0.20 - thresholds.minimumCountsCrossEnvironmentMaeImprovement(),
                0.50 - thresholds.maximumCountsSpearmanRegression(),
                0.20 + thresholds.maximumCountsWorstEnvironmentMaeRegression());
        ScenarioAblationPlanner.Decision decision = ScenarioAblationPlanner.decide(
                FeatureSelectionMode.AUTO_COUNTS_IF_VALIDATED,
                List.of(policy),
                List.of(ratio),
                List.of(environmentRatio, summary("host-b", ScenarioFeatureSet.RATIO_ONLY, 0.20, 0.50, 0.20)),
                List.of(
                        counts,
                        summary(
                                "host-b",
                                ScenarioFeatureSet.RATIO_AND_COUNTS,
                                0.20 - thresholds.minimumCountsCrossEnvironmentMaeImprovement(),
                                0.50 - thresholds.maximumCountsSpearmanRegression(),
                                0.20 + thresholds.maximumCountsWorstEnvironmentMaeRegression())),
                2,
                thresholds);
        assertThat(decision.contextPassed()).isTrue();
        assertThat(decision.countsPassed()).isTrue();
        assertThat(decision.selection().selectedFeatureSet()).isEqualTo(ScenarioFeatureSet.RATIO_AND_COUNTS);
        assertThat(decision.selection().selectedFeatureSet()).isNotEqualTo(ScenarioFeatureSet.POLICY_ONLY);
    }

    @Test
    void autoFallsBackButRequireCountsRejectsTheSameFailedGate() {
        EvaluationSummary policy = summary("scenario", ScenarioFeatureSet.POLICY_ONLY, 0.20, 0.50, 0.20);
        EvaluationSummary ratio = summary("scenario", ScenarioFeatureSet.RATIO_ONLY, 0.18, 0.55, 0.18);
        EvaluationSummary poorCounts = summary("host-a", ScenarioFeatureSet.RATIO_AND_COUNTS, 0.30, 0.40, 0.30);
        ScenarioAblationPlanner.Decision automatic = ScenarioAblationPlanner.decide(
                FeatureSelectionMode.AUTO_COUNTS_IF_VALIDATED,
                List.of(policy),
                List.of(ratio),
                List.of(ratio),
                List.of(poorCounts),
                2,
                EvaluationThresholds.defaults());
        assertThat(automatic.selection().selectedFeatureSet()).isEqualTo(ScenarioFeatureSet.RATIO_ONLY);
        assertThat(automatic.countsPassed()).isFalse();
        ScenarioAblationPlanner.Decision required = ScenarioAblationPlanner.decide(
                FeatureSelectionMode.REQUIRE_COUNTS,
                List.of(policy),
                List.of(ratio),
                List.of(ratio),
                List.of(poorCounts),
                2,
                EvaluationThresholds.defaults());
        assertThat(required.selection().selectedFeatureSet()).isEqualTo(ScenarioFeatureSet.RATIO_AND_COUNTS);
        assertThat(required.countsPassed()).isFalse();
    }

    @Test
    void memberSeedsAreStableAndDistinctAcrossEveryIdentityCoordinate() {
        long modelSeed = ScenarioTrainingConfig.defaults().modelSeed();
        long first = ScenarioMemberSeeds.derive(
                modelSeed, "VALIDATION_CONTEXT_LOSO", ScenarioFeatureSet.RATIO_ONLY, "fold-a", 0);
        assertThat(ScenarioMemberSeeds.derive(
                        modelSeed, "VALIDATION_CONTEXT_LOSO", ScenarioFeatureSet.RATIO_ONLY, "fold-a", 0))
                .isEqualTo(first);
        assertThat(ScenarioMemberSeeds.derive(
                        modelSeed, "VALIDATION_CONTEXT_LOSO", ScenarioFeatureSet.POLICY_ONLY, "fold-a", 0))
                .isNotEqualTo(first);
        assertThat(ScenarioMemberSeeds.derive(
                        modelSeed, "VALIDATION_CONTEXT_LOSO", ScenarioFeatureSet.RATIO_ONLY, "fold-b", 0))
                .isNotEqualTo(first);
        assertThat(ScenarioMemberSeeds.derive(
                        modelSeed, "VALIDATION_CONTEXT_LOSO", ScenarioFeatureSet.RATIO_ONLY, "fold-a", 1))
                .isNotEqualTo(first);
        assertThat(java.util.stream.IntStream.range(0, 3)
                        .mapToObj(index -> ScenarioMemberSeeds.derive(
                                2L, "PRODUCTION", ScenarioFeatureSet.RATIO_ONLY, "all", index))
                        .toList())
                .containsExactly(0xc066c6f196ffe6d3L, 0x9141c9205b7b043bL, 0x54445ee768f6d23bL);
    }

    @Test
    void missingPairedFoldCannotPassAContextOrCountsGate() {
        EvaluationSummary policy = summary("scenario-a", ScenarioFeatureSet.POLICY_ONLY, 0.30, 0.40, 0.30);
        EvaluationSummary ratio = summary("scenario-b", ScenarioFeatureSet.RATIO_ONLY, 0.10, 0.90, 0.10);
        EvaluationSummary environmentRatio = summary("host-a", ScenarioFeatureSet.RATIO_ONLY, 0.30, 0.40, 0.30);
        EvaluationSummary counts = summary("host-b", ScenarioFeatureSet.RATIO_AND_COUNTS, 0.10, 0.90, 0.10);
        ScenarioAblationPlanner.Decision decision = ScenarioAblationPlanner.decide(
                FeatureSelectionMode.AUTO_COUNTS_IF_VALIDATED,
                List.of(policy),
                List.of(ratio),
                List.of(environmentRatio),
                List.of(counts),
                2,
                EvaluationThresholds.defaults());
        assertThat(decision.contextPassed()).isFalse();
        assertThat(decision.countsPassed()).isFalse();
        assertThat(decision.selection().selectedFeatureSet()).isEqualTo(ScenarioFeatureSet.RATIO_ONLY);
    }
}
