package io.euhedral_execution.training.learning;

import java.nio.file.Path;
import java.util.Objects;

public record ScenarioTrainingArtifacts(Path modelDirectory, Path metadata,
        Path groupedEvaluation, Path losoEvaluation, Path ablationEvaluation,
        Path trainingHistory, ModelAcceptanceStatus acceptanceStatus,
        ScenarioFeatureSet selectedFeatureSet) {
    public ScenarioTrainingArtifacts {
        Objects.requireNonNull(modelDirectory);
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(groupedEvaluation);
        Objects.requireNonNull(losoEvaluation);
        Objects.requireNonNull(ablationEvaluation);
        Objects.requireNonNull(trainingHistory);
        Objects.requireNonNull(acceptanceStatus);
        Objects.requireNonNull(selectedFeatureSet);
    }
}
