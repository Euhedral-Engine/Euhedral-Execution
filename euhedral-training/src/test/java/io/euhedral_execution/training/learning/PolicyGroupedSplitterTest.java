package io.euhedral_execution.training.learning;

import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.scenarioResults;
import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.scenarios;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.EvaluationThresholds;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.data.PolicyGroupedSplit;
import io.euhedral_execution.training.learning.data.PolicyGroupedSplitter;
import io.euhedral_execution.training.learning.enums.FeatureSelectionMode;
import io.euhedral_execution.training.learning.enums.LearningPartition;
import io.euhedral_execution.training.learning.inputs.ScenarioDatasetAudit;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningReader;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningRow;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningTable;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResult;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class PolicyGroupedSplitterTest {
    @Test
    void fixedPolicyIdsHaveExactDefaultBuckets() {
        long seed = ScenarioTrainingConfig.defaults().splitSeed();
        List<LearningPartition> actual = java.util.stream.LongStream.range(0, 10)
                .mapToObj(value -> PolicyGroupedSplitter.partition(
                        new PolicyId(value), seed)).toList();
        assertThat(actual).containsExactly(
                LearningPartition.TEST, LearningPartition.TRAIN,
                LearningPartition.TRAIN, LearningPartition.TRAIN,
                LearningPartition.TRAIN, LearningPartition.TRAIN,
                LearningPartition.TRAIN, LearningPartition.TRAIN,
                LearningPartition.TRAIN, LearningPartition.TRAIN);
    }

    @Test void policyRowsCannotLeakAcrossSplits() throws Exception {
        SortedSet<SourceScenario> scenarios = new TreeSet<>(List.of(
                SourceScenario.of("a", 1, 4), SourceScenario.of("a", 2, 4)));
        List<ScenarioLearningRow> rows = new ArrayList<>();
        TreeMap<PolicyId, PolicyVector> policies = new TreeMap<>();
        for (int i = 0; i < 400; i++) {
            double[] weights = new double[28]; weights[0] = i; weights[1] = i % 10 / 10.0;
            PolicyVector p = PolicyVector.of(weights); policies.put(p.id(), p);
            double quality = (p.id().value() & 1L) == 0 ? .9 : .1;
            for (var s : scenarios) rows.add(row(p, s, quality));
        }
        rows.sort(null);
        var audit = new ScenarioDatasetAudit(policies.size(), 2, rows.size(), rows.size(),
                0, 0, 0, 0, 0, 0);
        var table = new ScenarioLearningTable(rows, policies, scenarios, audit, "0".repeat(64));
        ScenarioTrainingConfig config = new ScenarioTrainingConfig(
                ScenarioTrainingConfig.defaults().splitSeed(), 1, "cpu", 3, 1, 3, 1, 1, 1,
                .001f, .0001f, .02f, 1, 2, 1, 1, 1, 1, false, true,
                FeatureSelectionMode.RATIO_ONLY, EvaluationThresholds.defaults());
        PolicyGroupedSplit split = PolicyGroupedSplitter.split(table, config.splitSeed(), config);
        Map<PolicyId, Set<LearningPartition>> seen = new HashMap<>();
        split.trainingRows().forEach(r -> seen.computeIfAbsent(r.policy().id(), k -> new HashSet<>()).add(LearningPartition.TRAIN));
        split.validationRows().forEach(r -> seen.computeIfAbsent(r.policy().id(), k -> new HashSet<>()).add(LearningPartition.VALIDATION));
        split.testRows().forEach(r -> seen.computeIfAbsent(r.policy().id(), k -> new HashSet<>()).add(LearningPartition.TEST));
        assertThat(seen.values()).allMatch(s -> s.size() == 1);
        assertThat(Collections.disjoint(split.ablationEarlyStopPolicies(), split.ablationScorePolicies())).isTrue();
    }

    @Test
    void fixtureKeepsAllFourScenarioRowsAndAblationHalvesDisjoint() {
        ScenarioLearningTable table = ScenarioLearningReader.fromScenarioResults(
                scenarioResults(), scenarios(), false);
        PolicyGroupedSplit split = PolicyGroupedSplitter.split(table,
                ScenarioTrainingConfig.defaults().splitSeed(),
                ScenarioTrainingConfig.defaults());
        for (PolicyId policy : split.policyPartitions().keySet()) {
            LearningPartition partition = split.policyPartitions().get(policy);
            List<ScenarioLearningRow> rows = switch (partition) {
                case TRAIN -> split.trainingRows();
                case VALIDATION -> split.validationRows();
                case TEST -> split.testRows();
            };
            assertThat(rows.stream().filter(row -> row.policy().id().equals(policy))).hasSize(4);
        }
        assertThat(Collections.disjoint(split.ablationEarlyStopPolicies(),
                split.ablationScorePolicies())).isTrue();
        assertThat(split.ablationEarlyStopPolicies())
                .doesNotContainAnyElementsOf(split.ablationScorePolicies());
    }

    @Test
    void shuffledRowsAndAdditionalIdentitiesCannotMoveExistingPolicies() {
        List<ScenarioResult> original =
                new ArrayList<>(scenarioResults());
        ScenarioLearningTable first = ScenarioLearningReader.fromScenarioResults(
                original, scenarios(), false);
        Collections.shuffle(original, new Random(91));
        ScenarioLearningTable shuffled = ScenarioLearningReader.fromScenarioResults(
                original, scenarios(), false);
        ScenarioTrainingConfig config = ScenarioTrainingConfig.defaults();
        SortedMap<PolicyId, LearningPartition> assignments =
                PolicyGroupedSplitter.split(first, config.splitSeed(), config)
                        .policyPartitions();
        assertThat(PolicyGroupedSplitter.split(shuffled, config.splitSeed(), config)
                .policyPartitions()).isEqualTo(assignments);

        PolicyId added = new PolicyId(0xfedcba9876543210L);
        TreeMap<PolicyId, LearningPartition> extended = new TreeMap<>(assignments);
        extended.put(added, PolicyGroupedSplitter.partition(added, config.splitSeed()));
        assertThat(extended).containsAllEntriesOf(assignments);
        for (PolicyId policy : assignments.keySet()) {
            assertThat(PolicyGroupedSplitter.partition(policy, config.splitSeed()))
                    .isEqualTo(assignments.get(policy));
        }
    }

    @Test
    void losoAndLoeoHelpersExcludeHeldContextWithoutMovingPolicies() {
        ScenarioLearningTable table = ScenarioLearningReader.fromScenarioResults(
                scenarioResults(), scenarios(), false);
        PolicyGroupedSplit split = PolicyGroupedSplitter.split(table,
                ScenarioTrainingConfig.defaults().splitSeed(),
                ScenarioTrainingConfig.defaults());
        SourceScenario held = scenarios().first();
        assertThat(PolicyGroupedSplitter.withoutScenario(split.trainingRows(), held))
                .noneMatch(row -> row.scenario().equals(held));
        assertThat(PolicyGroupedSplitter.withoutScenario(split.validationRows(), held))
                .noneMatch(row -> row.scenario().equals(held));
        assertThat(PolicyGroupedSplitter.onlyScenario(split.testRows(), held))
                .allMatch(row -> row.scenario().equals(held));
        String environment = held.environmentId();
        assertThat(PolicyGroupedSplitter.withoutEnvironment(
                split.trainingRows(), environment))
                .noneMatch(row -> row.scenario().environmentId().equals(environment));
        assertThat(PolicyGroupedSplitter.withoutEnvironment(
                split.ablationEarlyStopRows(), environment))
                .noneMatch(row -> row.scenario().environmentId().equals(environment));
        Set<PolicyId> fit = split.trainingRows().stream().map(row -> row.policy().id())
                .collect(java.util.stream.Collectors.toSet());
        Set<PolicyId> score = split.testRows().stream().map(row -> row.policy().id())
                .collect(java.util.stream.Collectors.toSet());
        Set<PolicyId> early = split.validationRows().stream()
                .map(row -> row.policy().id())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(Collections.disjoint(fit, score)).isTrue();
        assertThat(Collections.disjoint(fit, early)).isTrue();
        assertThat(Collections.disjoint(early, score)).isTrue();
    }

    @Test
    void rejectsMinimumsInsteadOfFallingBackToRows() {
        ScenarioLearningTable table = ScenarioLearningReader.fromScenarioResults(
                scenarioResults(), scenarios(), false);
        ScenarioTrainingConfig impossible = new ScenarioTrainingConfig(
                ScenarioTrainingConfig.defaults().splitSeed(), 1, "cpu", 3, 1, 3,
                1, 1, 1, 0.001f, 0.0001f, 0.02f,
                10_000, 2, 1, 1, 1, 1, false, true,
                FeatureSelectionMode.RATIO_ONLY, EvaluationThresholds.defaults());
        assertThatThrownBy(() -> PolicyGroupedSplitter.split(
                table, impossible.splitSeed(), impossible))
                .isInstanceOf(InsufficientScenarioLearningDataException.class);
    }

    @Test
    void coldStartAllowsSparseTestScenarioCoverage() {
        ScenarioTrainingConfig strict = ScenarioTrainingConfig.defaults();
        ScenarioTrainingConfig coldStart = strict.coldStart();
        SourceScenario heldOut = scenarios().first();
        List<ScenarioResult> sparseTest = scenarioResults().stream().map(result ->
                result.scenario().equals(heldOut)
                        && PolicyGroupedSplitter.partition(
                        result.policy().id(), strict.splitSeed()) == LearningPartition.TEST
                        ? missing(result) : result).toList();
        ScenarioLearningTable table = ScenarioLearningReader.fromScenarioResults(
                sparseTest, scenarios(), false);

        assertThatThrownBy(() -> PolicyGroupedSplitter.split(
                table, strict.splitSeed(), strict))
                .isInstanceOf(InsufficientScenarioLearningDataException.class)
                .hasMessageContaining("test lacks rows for");

        PolicyGroupedSplit split = PolicyGroupedSplitter.split(
                table, coldStart.splitSeed(), coldStart);

        assertThat(split.trainingRows())
                .anyMatch(row -> row.scenario().equals(heldOut));
        assertThat(split.validationRows())
                .anyMatch(row -> row.scenario().equals(heldOut));
        assertThat(split.testRows())
                .noneMatch(row -> row.scenario().equals(heldOut));
    }

    @Test
    void rejectsMissingConstantAndNoTopDecilePartitions() {
        ScenarioTrainingConfig config = ScenarioTrainingConfig.defaults();
        List<ScenarioResult> source = scenarioResults();

        List<ScenarioResult> constant = source.stream().map(result ->
                PolicyGroupedSplitter.partition(result.policy().id(), config.splitSeed())
                        == LearningPartition.VALIDATION
                        ? result.withQuality(0.5) : result).toList();
        assertThatThrownBy(() -> PolicyGroupedSplitter.split(
                ScenarioLearningReader.fromScenarioResults(
                        constant, scenarios(), false),
                config.splitSeed(), config))
                .isInstanceOf(InsufficientScenarioLearningDataException.class);

        TreeMap<PolicyId, Double> belowTop = new TreeMap<>();
        source.stream().map(ScenarioResult::policy).map(PolicyVector::id).distinct()
                .filter(id -> PolicyGroupedSplitter.partition(id, config.splitSeed())
                        == LearningPartition.VALIDATION)
                .sorted().forEach(id -> belowTop.put(id,
                        belowTop.size() % 2 == 0 ? 0.2 : 0.8));
        List<ScenarioResult> noTop = source.stream().map(result ->
                belowTop.containsKey(result.policy().id())
                        ? result.withQuality(belowTop.get(result.policy().id())) : result).toList();
        assertThatThrownBy(() -> PolicyGroupedSplitter.split(
                ScenarioLearningReader.fromScenarioResults(noTop, scenarios(), false),
                config.splitSeed(), config))
                .isInstanceOf(InsufficientScenarioLearningDataException.class);

        SourceScenario missingScenario = scenarios().first();
        List<ScenarioResult> missing = source.stream().map(result ->
                result.scenario().equals(missingScenario)
                        && PolicyGroupedSplitter.partition(
                        result.policy().id(), config.splitSeed())
                        == LearningPartition.VALIDATION
                        ? missing(result) : result).toList();
        assertThatThrownBy(() -> PolicyGroupedSplitter.split(
                ScenarioLearningReader.fromScenarioResults(missing, scenarios(), false),
                config.splitSeed(), config))
                .isInstanceOf(InsufficientScenarioLearningDataException.class);
    }

    private static ScenarioResult missing(ScenarioResult result) {
        OptionalDouble empty = OptionalDouble.empty();
        return new ScenarioResult(result.scenario(), result.policy(),
                ScenarioResultStatus.MISSING, 0, 0, 0, 0, 0, 0,
                empty, empty, empty, empty, empty, empty, empty, empty,
                empty, empty, empty);
    }

    private static ScenarioLearningRow row(PolicyVector p, SourceScenario s, double q) {
        return new ScenarioLearningRow(p, s, ScenarioResultStatus.VALID_STRONG, q, 10, 9, 11,
                1, .1, 0);
    }
}
