package io.euhedral_execution.training.learning.inputs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import io.euhedral_execution.training.DataMerger;

public record ScenarioInputs(Path scenarioResults, Path robustLeaderVectors,
                             Path incompletePolicyVectors) {

    public static ScenarioInputs from(DataMerger.MergeArtifacts artifacts) {
        return new ScenarioInputs(artifacts.scenarioResults(), artifacts.robustLeaderVectors(),
                artifacts.incompleteVectors());
    }

    public ScenarioInputs {
        Objects.requireNonNull(scenarioResults);
        Objects.requireNonNull(robustLeaderVectors);
        Objects.requireNonNull(incompletePolicyVectors);
    }

    public void requireFiles() throws IOException {
        for (Path path : new Path[] {scenarioResults, robustLeaderVectors,
                incompletePolicyVectors}) {
            if (!Files.isRegularFile(path)) {
                throw new IOException("Not a regular Phase 1 input file: " + path);
            }
        }
    }
}
