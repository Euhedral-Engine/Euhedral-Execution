package io.euhedral_execution.training.learning.data;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.SourceScenario;

public record ScenarioLearningMatrix(
        int rows,
        int featureWidth,
        float[] features,
        float[] ordinalLabels,
        float[] rowWeights,
        double[] qualities,
        PolicyId[] policyIds,
        SourceScenario[] scenarios) {

    public ScenarioLearningMatrix {
        features = features.clone();
        ordinalLabels = ordinalLabels.clone();
        rowWeights = rowWeights.clone();
        qualities = qualities.clone();
        policyIds = policyIds.clone();
        scenarios = scenarios.clone();
        if (rows <= 0
                || featureWidth <= 0
                || features.length != (long) rows * featureWidth
                || ordinalLabels.length != (long) rows * 9
                || rowWeights.length != rows
                || qualities.length != rows
                || policyIds.length != rows
                || scenarios.length != rows) {
            throw new IllegalArgumentException("Invalid matrix shape");
        }
        for (float feature : features) {
            if (!Float.isFinite(feature)) {
                throw new IllegalArgumentException("Non-finite matrix feature");
            }
        }
        for (float label : ordinalLabels) {
            if (label != 0.0f && label != 1.0f) {
                throw new IllegalArgumentException("Matrix labels must be hard ordinal labels");
            }
        }
        for (int row = 0; row < rows; row++) {
            if (!Float.isFinite(rowWeights[row])
                    || rowWeights[row] <= 0
                    || !Double.isFinite(qualities[row])
                    || qualities[row] < 0
                    || qualities[row] > 1
                    || policyIds[row] == null
                    || scenarios[row] == null) {
                throw new IllegalArgumentException("Invalid matrix row");
            }
        }
    }

    @Override
    public float[] features() {
        return features.clone();
    }

    @Override
    public float[] ordinalLabels() {
        return ordinalLabels.clone();
    }

    @Override
    public float[] rowWeights() {
        return rowWeights.clone();
    }

    @Override
    public double[] qualities() {
        return qualities.clone();
    }

    @Override
    public PolicyId[] policyIds() {
        return policyIds.clone();
    }

    @Override
    public SourceScenario[] scenarios() {
        return scenarios.clone();
    }
}
