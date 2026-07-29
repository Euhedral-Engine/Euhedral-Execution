package io.euhedral_execution.training.learning.data;

import java.util.Objects;

import io.euhedral_execution.training.data.SourceScenario;

public record ScenarioPrediction(SourceScenario scenario, double predictedQuality,
                                 double ordinalStdDev, double qualityIntervalLow,
                                 double qualityIntervalHigh, double ordinalEntropy,
                                 double topDecileProbability, double epistemicStdDev,
                                 double disagreementRange) {

    public ScenarioPrediction {
        Objects.requireNonNull(scenario);
        for (double x : new double[] {predictedQuality, ordinalStdDev, qualityIntervalLow,
                qualityIntervalHigh, ordinalEntropy, topDecileProbability, epistemicStdDev,
                disagreementRange}) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException();
            }
        }
        if (predictedQuality < 0 || predictedQuality > 1 || ordinalStdDev < 0
                || qualityIntervalLow < 0 || qualityIntervalHigh > 1
                || qualityIntervalLow > qualityIntervalHigh || ordinalEntropy < 0
                || ordinalEntropy > 1 || topDecileProbability < 0 || topDecileProbability > 1
                || epistemicStdDev < 0 || disagreementRange < 0) {
            throw new IllegalArgumentException();
        }
    }
}
