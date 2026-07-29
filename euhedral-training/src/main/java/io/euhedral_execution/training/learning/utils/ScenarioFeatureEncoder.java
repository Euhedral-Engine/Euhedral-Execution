package io.euhedral_execution.training.learning.utils;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.InsufficientScenarioLearningDataException;
import io.euhedral_execution.training.learning.data.ScenarioLearningMatrix;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningRow;
import io.euhedral_execution.training.learning.metadata.FeatureNormalizer;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;

public final class ScenarioFeatureEncoder {

    public static FeatureNormalizer fit(List<ScenarioLearningRow> rows, ScenarioFeatureSet set) {
        return FeatureNormalizer.fit(rows, set);
    }

    public static ScenarioLearningMatrix matrix(List<ScenarioLearningRow> source,
            SortedSet<SourceScenario> active, FeatureNormalizer normalizer) {
        List<ScenarioLearningRow> rows = source.stream().filter(r -> active.contains(r.scenario()))
                .sorted().toList();
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Matrix rows are empty");
        }
        int f = normalizer.featureNames().size(), n = rows.size();
        float[] features = new float[n * f], labels = new float[n * 9], weights = new float[n];
        double[] qualities = new double[n];
        PolicyId[] ids = new PolicyId[n];
        SourceScenario[] scenarios = new SourceScenario[n];
        TreeMap<SourceScenario, Integer> counts = new TreeMap<>();
        for (var row : rows) {
            counts.merge(row.scenario(), 1, Integer::sum);
        }
        if (!counts.keySet().equals(active)) {
            throw new InsufficientScenarioLearningDataException("Every active scenario needs rows");
        }
        for (int i = 0; i < n; i++) {
            var row = rows.get(i);
            normalizer.encode(row.policy(), row.scenario(), features, i * f);
            ScenarioOrdinalTargets.encode(row.quality(), labels, i * 9);
            weights[i] = (float) (n / (double) (active.size() * counts.get(row.scenario())));
            qualities[i] = row.quality();
            ids[i] = row.policy().id();
            scenarios[i] = row.scenario();
        }
        return new ScenarioLearningMatrix(n, f, features, labels, weights, qualities, ids,
                scenarios);
    }

    private ScenarioFeatureEncoder() {
    }
}
