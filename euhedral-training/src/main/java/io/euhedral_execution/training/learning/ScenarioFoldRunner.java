package io.euhedral_execution.training.learning;

import ai.djl.Device;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.data.PolicyPredictionCurve;
import io.euhedral_execution.training.learning.data.ScenarioLearningMatrix;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningRow;
import io.euhedral_execution.training.learning.metadata.FeatureNormalizer;
import io.euhedral_execution.training.learning.output.EvaluationSummary;
import io.euhedral_execution.training.learning.output.TrainingHistoryEntry;
import io.euhedral_execution.training.learning.utils.ScenarioFeatureEncoder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

final class ScenarioFoldRunner {

    private ScenarioFoldRunner() {}

    static FoldResult run(
            String trainingKind,
            String evaluationKind,
            String foldId,
            ScenarioFeatureSet featureSet,
            List<ScenarioLearningRow> fittingRows,
            List<ScenarioLearningRow> earlyStopRows,
            List<ScenarioLearningRow> scoreRows,
            int memberCount,
            ScenarioTrainingConfig config,
            Device device,
            Path directory,
            boolean insufficientContextVariation)
            throws Exception {
        validateRowSets(fittingRows, earlyStopRows, scoreRows);
        SortedSet<SourceScenario> fittingScenarios = scenarios(fittingRows);
        SortedSet<SourceScenario> scoreScenarios = scenarios(scoreRows);
        FeatureNormalizer normalizer = ScenarioFeatureEncoder.fit(fittingRows, featureSet);
        ScenarioLearningMatrix fitting = ScenarioFeatureEncoder.matrix(fittingRows, fittingScenarios, normalizer);
        ScenarioLearningMatrix validation = ScenarioFeatureEncoder.matrix(earlyStopRows, fittingScenarios, normalizer);
        ArrayList<OrdinalMember> members = new ArrayList<>(memberCount);
        ArrayList<TrainingHistoryEntry> history = new ArrayList<>();
        try {
            for (int memberIndex = 0; memberIndex < memberCount; memberIndex++) {
                Path memberDirectory = directory.resolve("member-%03d".formatted(memberIndex));
                ScenarioOrdinalNetwork.TrainingResult trained = ScenarioOrdinalNetwork.train(
                        fitting,
                        validation,
                        featureSet,
                        config,
                        device,
                        trainingKind,
                        foldId,
                        memberIndex,
                        memberDirectory);
                members.add(trained.member());
                history.addAll(trained.history());
            }
            List<PolicyVector> policies = policies(scoreRows);
            try (ScenarioConditionedModel model =
                    ScenarioConditionedModel.forTest(normalizer, scoreScenarios, members)) {
                List<PolicyPredictionCurve> predictions =
                        model.predictCurves(policies, scoreScenarios, device.isGpu() ? 65_536 : 16_384);
                EvaluationSummary evaluation = ScenarioModelEvaluator.evaluate(
                        evaluationKind,
                        foldId,
                        featureSet,
                        scoreRows,
                        retainPredictionsForRows(predictions, scoreRows),
                        insufficientContextVariation);
                members.clear(); // ownership moved to and closed by the model
                return new FoldResult(evaluation, List.copyOf(history), normalizer, fittingScenarios, scoreScenarios);
            }
        } finally {
            for (OrdinalMember member : members) {
                member.close();
            }
        }
    }

    static SortedSet<SourceScenario> scenarios(List<ScenarioLearningRow> rows) {
        TreeSet<SourceScenario> scenarios = new TreeSet<>();
        for (ScenarioLearningRow row : rows) {
            scenarios.add(row.scenario());
        }
        if (scenarios.isEmpty()) {
            throw new InsufficientScenarioLearningDataException("Fold row set is empty");
        }
        return java.util.Collections.unmodifiableSortedSet(scenarios);
    }

    static int distinctRatios(List<ScenarioLearningRow> rows) {
        return (int) rows.stream().map(row -> row.scenario().ratio()).distinct().count();
    }

    static void validateRowSets(
            List<ScenarioLearningRow> fittingRows,
            List<ScenarioLearningRow> earlyStopRows,
            List<ScenarioLearningRow> scoreRows) {
        requireDisjointPolicies(fittingRows, earlyStopRows, scoreRows);
        if (!scenarios(earlyStopRows).equals(scenarios(fittingRows))) {
            throw new InsufficientScenarioLearningDataException(
                    "Fold early-stop scenarios do not match fitting scenarios");
        }
        scenarios(scoreRows);
    }

    static List<PolicyPredictionCurve> retainPredictionsForRows(
            List<PolicyPredictionCurve> predictions, List<ScenarioLearningRow> rows) {
        TreeMap<PolicyId, SortedSet<SourceScenario>> expected = new TreeMap<>();
        for (ScenarioLearningRow row : rows) {
            expected.computeIfAbsent(row.policy().id(), ignored -> new TreeSet<>())
                    .add(row.scenario());
        }
        ArrayList<PolicyPredictionCurve> retained = new ArrayList<>(expected.size());
        for (PolicyPredictionCurve curve : predictions) {
            SortedSet<SourceScenario> scenarios = expected.remove(curve.policy().id());
            if (scenarios == null) {
                throw new IllegalArgumentException("Prediction has no scoring policy row");
            }
            List<ScenarioPrediction> selected = curve.scenarios().stream()
                    .filter(prediction -> scenarios.contains(prediction.scenario()))
                    .toList();
            if (selected.size() != scenarios.size()) {
                throw new IllegalArgumentException("Prediction lacks a scoring scenario row");
            }
            retained.add(new PolicyPredictionCurve(curve.policy(), selected));
        }
        if (!expected.isEmpty()) {
            throw new IllegalArgumentException("Scoring policy lacks a prediction curve");
        }
        return List.copyOf(retained);
    }

    private static List<PolicyVector> policies(List<ScenarioLearningRow> rows) {
        TreeMap<PolicyId, PolicyVector> policies = new TreeMap<>();
        for (ScenarioLearningRow row : rows) {
            policies.put(row.policy().id(), row.policy());
        }
        return List.copyOf(policies.values());
    }

    private static void requireDisjointPolicies(
            List<ScenarioLearningRow> fitting, List<ScenarioLearningRow> earlyStop, List<ScenarioLearningRow> score) {
        HashSet<PolicyId> fit = ids(fitting);
        HashSet<PolicyId> early = ids(earlyStop);
        HashSet<PolicyId> scoring = ids(score);
        if (!java.util.Collections.disjoint(fit, early)
                || !java.util.Collections.disjoint(fit, scoring)
                || !java.util.Collections.disjoint(early, scoring)) {
            throw new IllegalArgumentException("Policy groups leak across fold row sets");
        }
    }

    private static HashSet<PolicyId> ids(List<ScenarioLearningRow> rows) {
        HashSet<PolicyId> result = new HashSet<>();
        for (ScenarioLearningRow row : rows) {
            result.add(row.policy().id());
        }
        return result;
    }

    record FoldResult(
            EvaluationSummary evaluation,
            List<TrainingHistoryEntry> history,
            FeatureNormalizer normalizer,
            SortedSet<SourceScenario> fittingScenarios,
            SortedSet<SourceScenario> scoreScenarios) {}
}
