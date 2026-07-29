package io.euhedral_execution.training.learning.output;

import java.nio.file.Path;
import java.util.Objects;

import io.euhedral_execution.training.learning.enums.ModelAcceptanceStatus;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;

public record ScenarioTrainingArtifacts(Path modelDirectory, Path metadata, Path groupedEvaluation,
                                        Path losoEvaluation, Path ablationEvaluation,
                                        Path trainingHistory,
                                        ModelAcceptanceStatus acceptanceStatus,
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
